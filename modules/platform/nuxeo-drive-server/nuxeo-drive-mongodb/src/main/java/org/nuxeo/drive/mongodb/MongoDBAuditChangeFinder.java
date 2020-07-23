/*
 * (C) Copyright 2020 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.nuxeo.drive.mongodb;

import static org.nuxeo.ecm.core.api.event.DocumentEventCategories.EVENT_DOCUMENT_CATEGORY;
import static org.nuxeo.ecm.platform.audit.api.BuiltinLogEntryData.LOG_CATEGORY;
import static org.nuxeo.ecm.platform.audit.api.BuiltinLogEntryData.LOG_ID;
import static org.nuxeo.ecm.platform.audit.api.BuiltinLogEntryData.LOG_LOG_DATE;
import static org.nuxeo.ecm.platform.audit.api.BuiltinLogEntryData.LOG_REPOSITORY_ID;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.drive.service.NuxeoDriveEvents;
import org.nuxeo.drive.service.SynchronizationRoots;
import org.nuxeo.drive.service.impl.AuditChangeFinder;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.repository.RepositoryManager;
import org.nuxeo.ecm.core.query.sql.model.OrderByExprs;
import org.nuxeo.ecm.core.query.sql.model.Predicates;
import org.nuxeo.ecm.core.query.sql.model.QueryBuilder;
import org.nuxeo.ecm.platform.audit.api.AuditQueryBuilder;
import org.nuxeo.ecm.platform.audit.api.AuditReader;
import org.nuxeo.ecm.platform.audit.api.ExtendedInfo;
import org.nuxeo.ecm.platform.audit.api.LogEntry;
import org.nuxeo.runtime.api.Framework;

/**
 * Override the JPA audit based change finder to execute query in BSON.
 * <p>
 * The structure of the query executed by the {@link AuditChangeFinder} is:
 *
 * <pre>
 * from LogEntry log where log.repositoryId = :repositoryId
 *
 * + AND if ActiveRoots (activeRoots) NOT empty
 *
 * from LogEntry log where log.repositoryId = :repositoryId and (
 * LIST_DOC_EVENTS_IDS_QUERY and ( ROOT_PATHS or COLECTIONS_PATHS) or
 * (log.category = 'NuxeoDrive' and log.eventId != 'rootUnregistered') )
 *
 *
 * if ActiveRoots EMPTY:
 *
 * from LogEntry log where log.repositoryId = :repositoryId and ((log.category =
 * 'NuxeoDrive' and log.eventId != 'rootUnregistered'))
 *
 * + AND (log.id > :lowerBound and log.id <= :upperBound) + order by
 * log.repositoryId asc, log.eventDate desc
 * </pre>
 *
 * @since 11.2
 */
public class MongoDBAuditChangeFinder extends AuditChangeFinder {

    private static final Logger log = LogManager.getLogger(MongoDBAuditChangeFinder.class);

    @Override
    public long getUpperBound() {
        RepositoryManager repositoryManager = Framework.getService(RepositoryManager.class);
        return getUpperBound(new HashSet<>(repositoryManager.getRepositoryNames()));
    }

    /**
     * Returns the last available log id in the audit index considering events older than the last clustering
     * invalidation date if clustering is enabled for at least one of the given repositories. This is to make sure the
     * {@code DocumentModel} further fetched from the session using the audit entry doc id is fresh.
     */
    @Override
    @SuppressWarnings("unchecked")
    public long getUpperBound(Set<String> repositoryNames) {
        long clusteringDelay = getClusteringDelay(repositoryNames);
        AuditReader auditService = Framework.getService(AuditReader.class);
        // var params = new HashMap<String, Object>();
        // StringBuilder auditQuerySb = new StringBuilder("{\"$and\":[");
        QueryBuilder queryBuilder = new AuditQueryBuilder().predicate(
                Predicates.in(LOG_REPOSITORY_ID, repositoryNames));
        if (clusteringDelay > -1) {
            // Double the delay in case of overlapping, see https://jira.nuxeo.com/browse/NXP-14826
            long lastClusteringInvalidationDate = System.currentTimeMillis() - 2 * clusteringDelay;
            // params.put("lastClusteringInvalidationDate", new Date(lastClusteringInvalidationDate));
            // auditQuerySb.append("{logDate:{$lt:${lastClusteringInvalidationDate}}},");
            queryBuilder.and(Predicates.lt(LOG_LOG_DATE, lastClusteringInvalidationDate));
        }
        queryBuilder.order(OrderByExprs.desc(LOG_ID));
        queryBuilder.limit(1);
        // auditQuerySb.append("{$orderby:{id:-1}}]}");
        // var entries = (List<LogEntry>) auditService.nativeQuery(auditQuerySb.toString(), params, 1, 1);
        var entries = (List<LogEntry>) auditService.queryLogs(queryBuilder);

        if (entries.isEmpty()) {
            if (clusteringDelay > -1) {
                // Check for existing entries without the clustering invalidation date filter to not return -1 in this
                // case and make sure the lower bound of the next call to NuxeoDriveManager#getChangeSummary will be >=
                // 0
                List<LogEntry> allEntries = (List<LogEntry>) auditService.nativeQuery("", 1, 1);
                if (!allEntries.isEmpty()) {
                    log.debug("Found no audit log entries matching the criterias but some exist, returning 0");
                    return 0;
                }
            }
            log.debug("Found no audit log entries, returning -1");
            return -1;
        }
        return entries.get(0).getId();
    }

    @Override
    protected List<LogEntry> queryAuditEntries(CoreSession session, SynchronizationRoots activeRoots,
            Set<String> collectionSyncRootMemberIds, long lowerBound, long upperBound, int limit) {
        AuditReader auditService = Framework.getService(AuditReader.class);
/*        // Set fixed query parameters
        QueryBuilder queryBuilder = new AuditQueryBuilder().predicate(
                Predicates.in(LOG_REPOSITORY_ID, session.getRepositoryName()));
        Map<String, Object> params = new HashMap<>();
        params.put("repositoryId", session.getRepositoryName());

        // Build query and set dynamic parameters
        StringBuilder auditQuerySb = new StringBuilder("from LogEntry log where ");
        auditQuerySb.append("log.repositoryId = :repositoryId");
        auditQuerySb.append(" and ");
        auditQuerySb.append("(");
        if (!activeRoots.getPaths().isEmpty()) {
            // detect changes under the currently active roots for the
            // current user
            queryBuilder.and(Predicates.eq(LOG_CATEGORY, EVENT_DOCUMENT_CATEGORY));
            auditQuerySb.append("(");
            auditQuerySb.append("log.category = 'eventDocumentCategory'");
            //TODO: don't hardcode event ids (contribute them?)
            auditQuerySb.append(
                    " and (log.eventId = 'documentCreated' or log.eventId = 'documentModified' or log.eventId = 'documentMoved' or log.eventId = 'documentCreatedByCopy' or log.eventId = 'documentRestored' or log.eventId = 'addedToCollection' or log.eventId = 'documentProxyPublished' or log.eventId = 'documentLocked' or log.eventId = 'documentUnlocked' or log.eventId = 'documentUntrashed')");
//            var events = List.of(DOCUMENT_CREATED, DOCUMENT_UPDATED, DOCUMENT_MOVED, DOCUMENT_CREATED_BY_COPY,
//                    DOCUMENT_RESTORED, ADDED_TO_COLLECTION, DOCUMENT_PROXY_PUBLISHED, DOCUMENT_LOCKED,
//                    DOCUMENT_UNLOCKED, DOCUMENT_UNTRASHED);
//            queryBuilder.and(Predicates.in(LOG_EVENT_ID, events));

            auditQuerySb.append(" or ");
            auditQuerySb.append("log.category = 'eventLifeCycleCategory'");
            auditQuerySb.append(" and log.eventId = 'lifecycle_transition_event' and log.docLifeCycle != 'deleted' ");
            auditQuerySb.append(") and s(");
            auditQuerySb.append("(");
            auditQuerySb.append(getCurrentRootFilteringClause(activeRoots.getPaths(), params));
            auditQuerySb.append(")");
            if (collectionSyncRootMemberIds != null && !collectionSyncRootMemberIds.isEmpty()) {
                auditQuerySb.append(" or (");
                auditQuerySb.append(getCollectionSyncRootFilteringClause(collectionSyncRootMemberIds, params));
                auditQuerySb.append(")");
            }
            auditQuerySb.append(") or ");
        }
        // Detect any root (un-)registration changes for the roots previously
        // seen by the current user.
        // Exclude 'rootUnregistered' since root unregistration is covered by a
        // "deleted" virtual event.
        auditQuerySb.append("(");
        auditQuerySb.append("log.category = '");
        auditQuerySb.append(NuxeoDriveEvents.EVENT_CATEGORY);
        auditQuerySb.append("' and log.eventId != 'rootUnregistered'");
        auditQuerySb.append(")");
        auditQuerySb.append(") and (");
        auditQuerySb.append(getJPARangeClause(lowerBound, upperBound, params));
        // we intentionally sort by eventDate even if the range filtering is
        // done on the log id: eventDate is useful to reflect the ordering of
        // events occurring inside the same transaction while the
        // monotonic behavior of log id is useful for ensuring that consecutive
        // range queries to the audit won't miss any events even when long
        // running transactions are logged after a delay.
        auditQuerySb.append(") order by log.repositoryId asc, log.eventDate desc");
        String auditQuery = auditQuerySb.toString();

        log.debug("Querying audit log for changes: {} with params: {}", auditQuery, params);

*/
        List<LogEntry> entries = (List<LogEntry>) auditService.queryLogs(new AuditQueryBuilder());

        // Post filter the output to remove (un)registration that are unrelated
        // to the current user.
        List<LogEntry> postFilteredEntries = new ArrayList<>();
        String principalName = session.getPrincipal().getName();
        for (LogEntry entry : entries) {
            ExtendedInfo impactedUserInfo = entry.getExtendedInfos().get("impactedUserName");
            if (impactedUserInfo != null && !principalName.equals(impactedUserInfo.getValue(String.class))) {
                // ignore event that only impact other users
                continue;
            }
            log.debug("Change detected: {}", entry);
            postFilteredEntries.add(entry);
        }
        return postFilteredEntries;
    }

}
