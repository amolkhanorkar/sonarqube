/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.sonar.server.rule;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.Multimap;
import org.apache.commons.lang.StringUtils;
import org.apache.ibatis.session.SqlSession;
import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.common.collect.Lists;
import org.elasticsearch.common.collect.Maps;
import org.elasticsearch.common.io.BytesStream;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.query.BoolFilterBuilder;
import org.elasticsearch.index.query.FilterBuilder;
import org.elasticsearch.index.query.FilterBuilders;
import org.elasticsearch.index.query.MatchQueryBuilder.Operator;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.sort.SortOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.utils.TimeProfiler;
import org.sonar.core.persistence.MyBatis;
import org.sonar.core.rule.*;
import org.sonar.core.technicaldebt.db.CharacteristicDao;
import org.sonar.core.technicaldebt.db.CharacteristicDto;
import org.sonar.server.es.ESIndex;
import org.sonar.server.es.SearchQuery;
import org.sonar.server.paging.PagedResult;
import org.sonar.server.paging.Paging;
import org.sonar.server.paging.PagingResult;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.newHashMap;
import static com.google.common.collect.Sets.newHashSet;
import static org.elasticsearch.index.query.FilterBuilders.*;
import static org.sonar.api.rules.Rule.STATUS_REMOVED;

/**
 * Fill search index with rules
 *
 * @since 4.1
 */
public class RuleRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(RuleRegistry.class);
  private static final TimeProfiler TIME_PROFILER = new TimeProfiler(LOG).setLevelToDebug();

  public static final String INDEX_RULES = "rules";
  public static final String TYPE_RULE = "rule";
  private static final String PARAM_NAMEORKEY = "nameOrKey";
  private static final String PARAM_STATUS = "status";

  private final ESIndex searchIndex;
  private final MyBatis myBatis;
  private final RuleDao ruleDao;
  private final CharacteristicDao characteristicDao;

  public RuleRegistry(ESIndex searchIndex, MyBatis myBatis, RuleDao ruleDao, CharacteristicDao characteristicDao) {
    this.searchIndex = searchIndex;
    this.myBatis = myBatis;
    this.ruleDao = ruleDao;
    this.characteristicDao = characteristicDao;
  }

  public void start() {
    searchIndex.addMappingFromClasspath(INDEX_RULES, TYPE_RULE, "/org/sonar/server/es/config/mappings/rule_mapping.json");
  }

  /**
   * Reindex all enabled and non manual rules
   */
  public String[] reindex() {
    SqlSession sqlSession = myBatis.openSession();
    try {
      return reindex(sqlSession);
    } finally {
      sqlSession.close();
    }
  }

  public String[] reindex(SqlSession session) {
    return reindex(ruleDao.selectEnablesAndNonManual(session), session);
  }

  /**
   * Reindex a given list of rules
   */
  public String[] reindex(Collection<RuleDto> rules, SqlSession session) {
    Multimap<Integer, RuleParamDto> paramsByRuleId = ArrayListMultimap.create();
    Multimap<Integer, RuleRuleTagDto> tagsByRuleId = ArrayListMultimap.create();
    Map<Integer, CharacteristicDto> characteristicsById = newHashMap();

    List<Integer> ruleIds = newArrayList();
    Collection<Integer> subCharacteristicIds = newHashSet();
    for (RuleDto ruleDto : rules) {
      ruleIds.add(ruleDto.getId());
      if (ruleDto.getDefaultSubCharacteristicId() != null) {
        subCharacteristicIds.add(ruleDto.getDefaultSubCharacteristicId());
      }
      if (ruleDto.getSubCharacteristicId() != null) {
        subCharacteristicIds.add(ruleDto.getSubCharacteristicId());
      }
    }

    List<CharacteristicDto> allCharacteristicDtos = characteristicDao.selectCharacteristicsByIds(subCharacteristicIds, session);
    Collection<Integer> characteristicIds = newHashSet();
    for (CharacteristicDto subCharacteristicDto : allCharacteristicDtos) {
      characteristicIds.add(subCharacteristicDto.getParentId());
    }
    allCharacteristicDtos.addAll(characteristicDao.selectCharacteristicsByIds(characteristicIds, session));

    for (RuleParamDto paramDto : ruleDao.selectParametersByRuleIds(ruleIds, session)) {
      paramsByRuleId.put(paramDto.getRuleId(), paramDto);
    }
    for (RuleRuleTagDto tagDto : ruleDao.selectTagsByRuleIds(ruleIds, session)) {
      tagsByRuleId.put(tagDto.getRuleId(), tagDto);
    }
    for (CharacteristicDto characteristicDto : allCharacteristicDtos) {
      characteristicsById.put(characteristicDto.getId(), characteristicDto);
    }
    return bulkIndexRules(rules, characteristicsById, paramsByRuleId, tagsByRuleId);
  }

  /**
   * Reindex one rule
   */
  public void reindex(RuleDto rule) {
    SqlSession sqlSession = myBatis.openSession();
    try {
      reindex(rule, sqlSession);
    } finally {
      sqlSession.close();
    }
  }

  public void reindex(RuleDto rule, SqlSession session) {
    try {
      Integer ruleId = rule.getId();
      Integer effectiveSubCharacteristicId = rule.getSubCharacteristicId() != null ? rule.getSubCharacteristicId() : rule.getDefaultSubCharacteristicId();
      CharacteristicDto subCharacteristic = effectiveSubCharacteristicId != null ? characteristicDao.selectById(effectiveSubCharacteristicId, session) : null;
      // The parent id of the sub-characteristic should never be null
      Integer parentId = subCharacteristic!= null ? subCharacteristic.getParentId() : null;
      CharacteristicDto characteristic = (subCharacteristic != null && parentId != null) ?
        characteristicDao.selectById(parentId, session) : null;
      searchIndex.putSynchronous(INDEX_RULES, TYPE_RULE, Long.toString(ruleId), ruleDocument(rule,
        characteristic, subCharacteristic,
        ruleDao.selectParametersByRuleIds(newArrayList(ruleId), session),
        ruleDao.selectTagsByRuleIds(newArrayList(ruleId), session)));
    } catch (IOException ioexception) {
      throw new IllegalStateException("Unable to index rule with id=" + rule.getId(), ioexception);
    }
  }

  private String[] bulkIndexRules(Collection<RuleDto> rules, Map<Integer, CharacteristicDto> characteristicsById, Multimap<Integer, RuleParamDto> paramsByRule,
                                  Multimap<Integer, RuleRuleTagDto> tagsByRule) {
    try {
      String[] ids = new String[rules.size()];
      BytesStream[] docs = new BytesStream[rules.size()];
      int index = 0;
      TIME_PROFILER.start("Build rules documents");
      for (RuleDto rule : rules) {
        ids[index] = rule.getId().toString();
        CharacteristicDto effectiveSubCharacteristic =
          characteristicsById.get(rule.getSubCharacteristicId() != null ? rule.getSubCharacteristicId() : rule.getDefaultSubCharacteristicId());
        CharacteristicDto effectiveCharacteristic = effectiveSubCharacteristic != null ? characteristicsById.get(effectiveSubCharacteristic.getParentId()) : null;
        docs[index] = ruleDocument(rule, effectiveCharacteristic, effectiveSubCharacteristic, paramsByRule.get(rule.getId()), tagsByRule.get(rule.getId()));
        index++;
      }
      TIME_PROFILER.stop();

      if (!rules.isEmpty()) {
        TIME_PROFILER.start("Index rules");
        searchIndex.bulkIndex(INDEX_RULES, TYPE_RULE, ids, docs);
        TIME_PROFILER.stop();
      }
      return ids;
    } catch (IOException ioe) {
      throw new IllegalStateException("Unable to index rules", ioe);
    }
  }

  public void removeDeletedRules(String[] ids) {
    List<String> indexIds = searchIndex.findDocumentIds(SearchQuery.create().index(INDEX_RULES).type(TYPE_RULE));
    indexIds.removeAll(Arrays.asList(ids));
    if (!indexIds.isEmpty()) {
      TIME_PROFILER.start("Remove deleted rule documents");
      searchIndex.bulkDelete(INDEX_RULES, TYPE_RULE, indexIds.toArray(new String[0]));
      TIME_PROFILER.stop();
    }
  }

  private XContentBuilder ruleDocument(RuleDto rule, @Nullable CharacteristicDto characteristicDto, @Nullable CharacteristicDto subCharacteristicDto,
                                       Collection<RuleParamDto> params, Collection<RuleRuleTagDto> tags) throws IOException {
    XContentBuilder document = XContentFactory.jsonBuilder()
      .startObject()
      .field(RuleDocument.FIELD_ID, rule.getId())
      .field(RuleDocument.FIELD_KEY, rule.getRuleKey())
      .field(RuleDocument.FIELD_LANGUAGE, rule.getLanguage())
      .field(RuleDocument.FIELD_NAME, rule.getName())
      .field(RuleDocument.FIELD_DESCRIPTION, rule.getDescription())
      .field(RuleDocument.FIELD_TEMPLATE_ID, rule.getParentId())
      .field(RuleDocument.FIELD_REPOSITORY_KEY, rule.getRepositoryKey())
      .field(RuleDocument.FIELD_SEVERITY, rule.getSeverityString())
      .field(RuleDocument.FIELD_STATUS, rule.getStatus())
      .field(RuleDocument.FIELD_CARDINALITY, rule.getCardinality())
      .field(RuleDocument.FIELD_CREATED_AT, rule.getCreatedAt())
      .field(RuleDocument.FIELD_UPDATED_AT, rule.getUpdatedAt());
    addRuleDebt(document, rule, characteristicDto, subCharacteristicDto);
    addRuleNote(document, rule);
    addRuleParams(document, rule, params);
    addRuleTags(document, rule, tags);
    document.endObject();
    return document;
  }

  private void addRuleDebt(XContentBuilder document, RuleDto rule, @Nullable CharacteristicDto characteristicDto, @Nullable CharacteristicDto subCharacteristicDto)
    throws IOException {
    if (characteristicDto != null && subCharacteristicDto != null) {
      boolean isFunctionOverridden = rule.getRemediationFunction() != null;
      document
        .field(RuleDocument.FIELD_CHARACTERISTIC_ID, characteristicDto.getId())
        .field(RuleDocument.FIELD_CHARACTERISTIC_KEY, characteristicDto.getKey())
        .field(RuleDocument.FIELD_SUB_CHARACTERISTIC_ID, subCharacteristicDto.getId())
        .field(RuleDocument.FIELD_SUB_CHARACTERISTIC_KEY, subCharacteristicDto.getKey())
        .field(RuleDocument.FIELD_REMEDIATION_FUNCTION, isFunctionOverridden ? rule.getRemediationFunction() : rule.getDefaultRemediationFunction())
        .field(RuleDocument.FIELD_REMEDIATION_COEFFICIENT, isFunctionOverridden ? rule.getRemediationCoefficient() : rule.getDefaultRemediationCoefficient())
        .field(RuleDocument.FIELD_REMEDIATION_OFFSET, isFunctionOverridden ? rule.getRemediationOffset() : rule.getDefaultRemediationOffset());
    }
  }

  private void addRuleNote(XContentBuilder document, RuleDto rule) throws IOException {
    if (rule.getNoteData() != null || rule.getNoteUserLogin() != null) {
      document.startObject(RuleDocument.FIELD_NOTE)
        .field(RuleDocument.FIELD_NOTE_DATA, rule.getNoteData())
        .field(RuleDocument.FIELD_NOTE_USER_LOGIN, rule.getNoteUserLogin())
        .field(RuleDocument.FIELD_NOTE_CREATED_AT, rule.getNoteCreatedAt())
        .field(RuleDocument.FIELD_NOTE_UPDATED_AT, rule.getNoteUpdatedAt())
        .endObject();
    }
  }

  private void addRuleParams(XContentBuilder document, RuleDto rule, Collection<RuleParamDto> params) throws IOException {
    if (!params.isEmpty()) {
      document.startArray(RuleDocument.FIELD_PARAMS);
      for (RuleParamDto param : params) {
        document.startObject()
          .field(RuleDocument.FIELD_PARAM_KEY, param.getName())
          .field(RuleDocument.FIELD_PARAM_TYPE, param.getType())
          .field(RuleDocument.FIELD_PARAM_DEFAULT_VALUE, param.getDefaultValue())
          .field(RuleDocument.FIELD_PARAM_DESCRIPTION, param.getDescription())
          .endObject();
      }
      document.endArray();
    }
  }

  private void addRuleTags(XContentBuilder document, RuleDto rule, Collection<RuleRuleTagDto> tags) throws IOException {
    List<String> systemTags = Lists.newArrayList();
    List<String> adminTags = Lists.newArrayList();
    for (RuleRuleTagDto tag : tags) {
      if (tag.getType() == RuleTagType.SYSTEM) {
        systemTags.add(tag.getTag());
      } else {
        adminTags.add(tag.getTag());
      }
    }
    if (!systemTags.isEmpty()) {
      document.array(RuleDocument.FIELD_SYSTEM_TAGS, systemTags.toArray());
    }
    if (!adminTags.isEmpty()) {
      document.array(RuleDocument.FIELD_ADMIN_TAGS, adminTags.toArray());
    }
  }


  /**
   * <p>Find rule IDs matching the given criteria.</p>
   *
   * @param query <p>A collection of (optional) criteria with the following meaning:
   *              <ul>
   *              <li><em>nameOrKey</em>: will be used as a query string over the "name" field</li>
   *              <li><em>&lt;anyField&gt;</em>: will be used to match the given field against the passed value(s);
   *              mutiple values must be separated by the '<code>|</code>' (vertical bar) character</li>
   *              </ul>
   *              </p>
   * @return
   */
  public List<Integer> findIds(Map<String, String> query) {
    Map<String, String> params = Maps.newHashMap(query);

    SearchQuery searchQuery = SearchQuery.create();
    searchQuery.index(INDEX_RULES).type(TYPE_RULE).scrollSize(500);

    if (params.containsKey(PARAM_NAMEORKEY)) {
      searchQuery.searchString(params.remove(PARAM_NAMEORKEY));
    }
    if (!params.containsKey(PARAM_STATUS)) {
      searchQuery.notField(PARAM_STATUS, STATUS_REMOVED);
    }

    for (Map.Entry<String, String> param : params.entrySet()) {
      searchQuery.field(param.getKey(), param.getValue().split("\\|"));
    }

    try {
      List<Integer> result = newArrayList();
      for (String docId : searchIndex.findDocumentIds(searchQuery)) {
        result.add(Integer.parseInt(docId));
      }
      return result;
    } catch (ElasticSearchException searchException) {
      throw new IllegalArgumentException("Unable to perform search, please check query", searchException);
    }
  }

  public PagedResult<Rule> find(RuleQuery query) {
    BoolFilterBuilder mainFilter = convertRuleQueryToFilterBuilder(query);

    Builder<Rule> rulesBuilder = ImmutableList.builder();
    SearchRequestBuilder searchRequestBuilder =
      searchIndex.client().prepareSearch(INDEX_RULES).setTypes(TYPE_RULE)
        .setPostFilter(mainFilter)
        .addSort(RuleDocument.FIELD_NAME, SortOrder.ASC);

    if (RuleQuery.NO_PAGINATION == query.pageSize()) {
      final int scrollTime = 100;
      SearchResponse scrollResp = searchRequestBuilder
        .setSearchType(SearchType.SCAN)
        .setScroll(new TimeValue(scrollTime))
        .setSize(50).execute().actionGet();
      //Scroll until no hits are returned
      while (true) {
        scrollResp = searchIndex.client().prepareSearchScroll(scrollResp.getScrollId()).setScroll(new TimeValue(scrollTime)).execute().actionGet();
        for (SearchHit hit : scrollResp.getHits()) {
          rulesBuilder.add(RuleDocumentParser.parse(hit.sourceAsMap()));
        }
        //Break condition: No hits are returned
        if (scrollResp.getHits().getHits().length == 0) {
          break;
        }
      }
      return new PagedResult<Rule>(rulesBuilder.build(), null);

    } else {
      Paging paging = Paging.create(query.pageSize(), query.pageIndex());
      SearchHits hits = searchIndex.executeRequest(
        searchRequestBuilder
          .setSize(paging.pageSize())
          .setFrom(paging.offset())
      );

      for (SearchHit hit : hits.hits()) {
        rulesBuilder.add(RuleDocumentParser.parse(hit.sourceAsMap()));
      }
      return new PagedResult<Rule>(rulesBuilder.build(), PagingResult.create(paging.pageSize(), paging.pageIndex(), hits.getTotalHits()));
    }
  }

  private static BoolFilterBuilder convertRuleQueryToFilterBuilder(RuleQuery query){
    BoolFilterBuilder mainFilter = boolFilter().mustNot(termFilter(RuleDocument.FIELD_STATUS, STATUS_REMOVED));
    if (StringUtils.isNotBlank(query.searchQuery())) {
      mainFilter.must(FilterBuilders.queryFilter(
        QueryBuilders.multiMatchQuery(query.searchQuery(), RuleDocument.FIELD_NAME + ".search", RuleDocument.FIELD_KEY).operator(Operator.AND)));
    }
    addMustTermOrTerms(mainFilter, RuleDocument.FIELD_LANGUAGE, query.languages());
    addMustTermOrTerms(mainFilter, RuleDocument.FIELD_REPOSITORY_KEY, query.repositories());
    addMustTermOrTerms(mainFilter, RuleDocument.FIELD_SEVERITY, query.severities());
    addMustTermOrTerms(mainFilter, RuleDocument.FIELD_STATUS, query.statuses());
    if (!query.tags().isEmpty()) {
      mainFilter.must(FilterBuilders.queryFilter(
          QueryBuilders.multiMatchQuery(query.tags(), RuleDocument.FIELD_ADMIN_TAGS, RuleDocument.FIELD_SYSTEM_TAGS).operator(Operator.OR))
      );
    }
    if (!query.debtCharacteristics().isEmpty()) {
      mainFilter.must(FilterBuilders.queryFilter(
          QueryBuilders.multiMatchQuery(query.debtCharacteristics(), RuleDocument.FIELD_CHARACTERISTIC_KEY, RuleDocument.FIELD_SUB_CHARACTERISTIC_KEY).operator(Operator.OR))
      );
    }
    if (query.hasDebtCharacteristic() != null) {
      if (Boolean.TRUE.equals(query.hasDebtCharacteristic())) {
        mainFilter.must(FilterBuilders.existsFilter(RuleDocument.FIELD_CHARACTERISTIC_KEY));
      } else {
        mainFilter.mustNot(FilterBuilders.existsFilter(RuleDocument.FIELD_CHARACTERISTIC_KEY));
      }
    }
    return mainFilter;
  }

  private static void addMustTermOrTerms(BoolFilterBuilder filter, String field, Collection<String> terms) {
    FilterBuilder termOrTerms = getTermOrTerms(field, terms);
    if (termOrTerms != null) {
      filter.must(termOrTerms);
    }
  }

  private static FilterBuilder getTermOrTerms(String field, Collection<String> terms) {
    if (terms.isEmpty()) {
      return null;
    } else {
      if (terms.size() == 1) {
        return termFilter(field, terms.iterator().next());
      } else {
        return termsFilter(field, terms.toArray());
      }
    }
  }

  @CheckForNull
  public Rule findByKey(RuleKey key) {
    final SearchHits hits = searchIndex.executeRequest(searchIndex.client().prepareSearch(INDEX_RULES).setTypes(TYPE_RULE)
      .setPostFilter(boolFilter()
        .must(
          termFilter(RuleDocument.FIELD_REPOSITORY_KEY, key.repository()),
          termFilter(RuleDocument.FIELD_KEY, key.rule())
        )));
    if (hits.totalHits() == 0) {
      return null;
    } else {
      return RuleDocumentParser.parse(hits.hits()[0].sourceAsMap());
    }
  }
}
