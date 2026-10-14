/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/

package org.apache.ofbiz.finance.common;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Common work management (STEP L7): human tasks of a contract as core
 * {@code WorkEffort} rows (type TASK), linked to the contract by
 * {@code FinanceContractWorkEffort}.
 *
 * <ul>
 * <li>One task per contract x work type x trigger key ({@link #ensureTask} is idempotent).</li>
 * <li>A task's status only says whether the work is done; business statuses are changed
 *     by the layer services, which then complete the task ({@link #completeTask}).</li>
 * </ul>
 */
public final class FinanceWorkEffortHelper {

    private static final String MODULE = FinanceWorkEffortHelper.class.getName();
    private static final String RESOURCE = "FinanceUiLabels";

    public static final String TYPE_EXPIRY_NOTICE = "FINWE_EXPIRY_NOTICE";
    public static final String TYPE_ASSET_RETURN = "FINWE_ASSET_RETURN";
    public static final String TYPE_ASSET_DISPOSAL = "FINWE_ASSET_DISPOSAL";
    public static final String TYPE_OWNERSHIP_TRANSFER = "FINWE_OWN_TRANSFER";
    public static final String TYPE_MAINTENANCE = "FINWE_MAINTENANCE";
    public static final String TYPE_OTHER = "FINWE_OTHER";

    public static final String STATUS_OPEN = "CAL_NEEDS_ACTION";
    public static final String STATUS_COMPLETED = "CAL_COMPLETED";
    public static final String STATUS_CANCELLED = "CAL_CANCELLED";

    private FinanceWorkEffortHelper() {
    }

    /** Result of {@link #ensureTask}. */
    public static final class Task {
        private final String workEffortId;
        private final boolean created;

        Task(String workEffortId, boolean created) {
            this.workEffortId = workEffortId;
            this.created = created;
        }

        public String getWorkEffortId() {
            return workEffortId;
        }

        public boolean isCreated() {
            return created;
        }
    }

    private static void check(Map<String, Object> result) throws GenericServiceException {
        if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
            throw new GenericServiceException(ServiceUtil.getErrorMessage(result));
        }
    }

    /** The link of a contract task, or null. */
    public static GenericValue findLink(Delegator delegator, String financeContractId, String workTypeEnumId,
            String triggerKey) throws GenericEntityException {
        return EntityQuery.use(delegator).from("FinanceContractWorkEffort")
                .where("financeContractId", financeContractId, "financeWorkTypeEnumId", workTypeEnumId,
                        "triggerKey", triggerKey).cache(false).queryFirst();
    }

    /**
     * Create the task of a contract for a work type and trigger key unless it already
     * exists. Optional: assignee party, fixed asset, estimated start / due date.
     */
    public static Task ensureTask(Delegator delegator, LocalDispatcher dispatcher, String financeContractId,
            String workTypeEnumId, String triggerKey, String name, String description, Timestamp estimatedStart,
            Timestamp dueDate, String assigneePartyId, String fixedAssetId, GenericValue userLogin)
            throws GenericEntityException, GenericServiceException {
        GenericValue existing = findLink(delegator, financeContractId, workTypeEnumId, triggerKey);
        if (existing != null) {
            return new Task(existing.getString("workEffortId"), false);
        }
        Map<String, Object> input = new HashMap<>();
        input.put("workEffortTypeId", "TASK");
        input.put("currentStatusId", STATUS_OPEN);
        input.put("workEffortName", name);
        input.put("description", description);
        input.put("estimatedStartDate", estimatedStart);
        input.put("estimatedCompletionDate", dueDate);
        input.put("userLogin", userLogin);
        Map<String, Object> created = dispatcher.runSync("createWorkEffort", input);
        check(created);
        String workEffortId = (String) created.get("workEffortId");
        if (UtilValidate.isNotEmpty(assigneePartyId)) {
            check(dispatcher.runSync("ensurePartyRole", UtilMisc.toMap("partyId", assigneePartyId,
                    "roleTypeId", "CAL_OWNER", "userLogin", userLogin)));
            check(dispatcher.runSync("assignPartyToWorkEffort", UtilMisc.toMap("workEffortId", workEffortId,
                    "partyId", assigneePartyId, "roleTypeId", "CAL_OWNER", "statusId", "PRTYASGN_ASSIGNED",
                    "userLogin", userLogin)));
        }
        if (UtilValidate.isNotEmpty(fixedAssetId)) {
            check(dispatcher.runSync("createWorkEffortFixedAssetAssign", UtilMisc.toMap("workEffortId", workEffortId,
                    "fixedAssetId", fixedAssetId, "statusId", "FA_ASGN_ASSIGNED", "userLogin", userLogin)));
        }
        GenericValue link = delegator.makeValue("FinanceContractWorkEffort");
        link.set("financeContractId", financeContractId);
        link.set("workEffortId", workEffortId);
        link.set("financeWorkTypeEnumId", workTypeEnumId);
        link.set("triggerKey", triggerKey);
        link.set("createdDate", UtilDateTime.nowTimestamp());
        link.create();
        return new Task(workEffortId, true);
    }

    /**
     * Complete the task of a contract for a work type and trigger key (no-op when it
     * does not exist or is already closed). Returns the workEffortId or null.
     */
    public static String completeTask(Delegator delegator, LocalDispatcher dispatcher, String financeContractId,
            String workTypeEnumId, String triggerKey, Timestamp completionDate, GenericValue userLogin)
            throws GenericEntityException, GenericServiceException {
        GenericValue link = findLink(delegator, financeContractId, workTypeEnumId, triggerKey);
        if (link == null) {
            return null;
        }
        String workEffortId = link.getString("workEffortId");
        GenericValue task = EntityQuery.use(delegator).from("WorkEffort").where("workEffortId", workEffortId)
                .cache(false).queryOne();
        if (task == null || STATUS_COMPLETED.equals(task.getString("currentStatusId"))
                || STATUS_CANCELLED.equals(task.getString("currentStatusId"))) {
            return workEffortId;
        }
        check(dispatcher.runSync("updateWorkEffort", UtilMisc.toMap("workEffortId", workEffortId,
                "currentStatusId", STATUS_COMPLETED,
                "actualCompletionDate", completionDate == null ? UtilDateTime.nowTimestamp() : completionDate,
                "userLogin", userLogin)));
        return workEffortId;
    }

    /**
     * Cancel the open task of a contract for a work type and trigger key (no-op when it
     * does not exist or is already closed). Returns the workEffortId or null.
     */
    public static String cancelTask(Delegator delegator, LocalDispatcher dispatcher, String financeContractId,
            String workTypeEnumId, String triggerKey, GenericValue userLogin)
            throws GenericEntityException, GenericServiceException {
        GenericValue link = findLink(delegator, financeContractId, workTypeEnumId, triggerKey);
        if (link == null) {
            return null;
        }
        String workEffortId = link.getString("workEffortId");
        GenericValue task = EntityQuery.use(delegator).from("WorkEffort").where("workEffortId", workEffortId)
                .cache(false).queryOne();
        if (task == null || STATUS_COMPLETED.equals(task.getString("currentStatusId"))
                || STATUS_CANCELLED.equals(task.getString("currentStatusId"))) {
            return workEffortId;
        }
        check(dispatcher.runSync("updateWorkEffort", UtilMisc.toMap("workEffortId", workEffortId,
                "currentStatusId", STATUS_CANCELLED, "userLogin", userLogin)));
        return workEffortId;
    }

    /** Tasks of a contract (link fields + WorkEffort name, status, dates). */
    public static List<GenericValue> listTasks(Delegator delegator, String financeContractId)
            throws GenericEntityException {
        return EntityQuery.use(delegator).from("FinanceContractWorkEffort")
                .where("financeContractId", financeContractId).orderBy("createdDate").cache(false).queryList();
    }

    /** Number of open (not completed / cancelled) tasks of a contract. */
    public static long countOpenTasks(Delegator delegator, String financeContractId) throws GenericEntityException {
        long open = 0;
        for (GenericValue link : listTasks(delegator, financeContractId)) {
            GenericValue task = EntityQuery.use(delegator).from("WorkEffort")
                    .where("workEffortId", link.getString("workEffortId")).cache(false).queryOne();
            String status = task == null ? null : task.getString("currentStatusId");
            if (task != null && !STATUS_COMPLETED.equals(status) && !STATUS_CANCELLED.equals(status)) {
                open++;
            }
        }
        return open;
    }

    // ------------------------------------------------------------------
    // Services
    // ------------------------------------------------------------------

    /** Service: create (or return) a task of any contract (loan or lease). */
    public static Map<String, Object> createFinanceContractTask(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        String financeContractId = (String) context.get("financeContractId");
        try {
            if (EntityQuery.use(delegator).from("FinanceContract").where("financeContractId", financeContractId)
                    .queryOne() == null) {
                return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, "FinanceRecordNotFound",
                        UtilMisc.toMap("entityName", "FinanceContract", "id", financeContractId),
                        (java.util.Locale) context.get("locale")));
            }
            String workType = UtilValidate.isNotEmpty((String) context.get("financeWorkTypeEnumId"))
                    ? (String) context.get("financeWorkTypeEnumId") : TYPE_OTHER;
            String triggerKey = UtilValidate.isNotEmpty((String) context.get("triggerKey"))
                    ? (String) context.get("triggerKey") : "MANUAL-" + delegator.getNextSeqId("FinanceContractWorkEffort");
            FinanceContractHelper.lock(delegator, financeContractId);
            Task task = ensureTask(delegator, dctx.getDispatcher(), financeContractId, workType, triggerKey,
                    (String) context.get("workEffortName"), (String) context.get("description"), null,
                    (Timestamp) context.get("dueDate"), (String) context.get("assigneePartyId"),
                    (String) context.get("fixedAssetId"), (GenericValue) context.get("userLogin"));
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("workEffortId", task.getWorkEffortId());
            result.put("taskCreated", task.isCreated());
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }
}
