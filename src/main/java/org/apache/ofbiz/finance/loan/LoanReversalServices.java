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

package org.apache.ofbiz.finance.loan;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import org.apache.ofbiz.finance.common.FinanceContractHelper;
import org.apache.ofbiz.finance.common.FinanceGlHelper;
import org.apache.ofbiz.finance.common.FinanceReversalHelper;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Reversal of mistaken loan operations (取消, STEP L15a): the contract activation,
 * a prepayment and a change of terms. Same policy as the lease layer: only when
 * nothing was processed after the operation; reversing GL entries; the schedule is
 * restored as a new revision; a {@code FinanceReversal} record. FINANCE_ADMIN only,
 * a reason is required.
 */
public final class LoanReversalServices {

    private static final String MODULE = LoanReversalServices.class.getName();
    private static final String RESOURCE = "FinanceUiLabels";

    private LoanReversalServices() {
    }

    private static Map<String, Object> error(String key, Map<String, ? extends Object> args, Locale locale) {
        return ServiceUtil.returnError(UtilProperties.getMessage(RESOURCE, key, args, locale));
    }

    private static Map<String, Object> blocked(String what, Locale locale) {
        return error("FinanceReversalBlocked", UtilMisc.toMap("what", what), locale);
    }

    private static void check(Map<String, Object> result) throws GenericServiceException {
        if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
            throw new GenericServiceException(ServiceUtil.getErrorMessage(result));
        }
    }

    private static GenericValue findAgreement(Delegator delegator, String loanAgreementId) throws GenericEntityException {
        return EntityQuery.use(delegator).from("LoanAgreement").where("loanAgreementId", loanAgreementId)
                .cache(false).queryOne();
    }

    private static long count(Delegator delegator, String entity, Timestamp after, Object... fv) throws GenericEntityException {
        return FinanceReversalHelper.countCreatedAfter(delegator, entity, after, fv);
    }

    /** Copy a schedule revision as a new revision. */
    private static void copyRevision(Delegator delegator, String loanAgreementId, long fromRevision, long toRevision)
            throws GenericEntityException {
        for (GenericValue row : EntityQuery.use(delegator).from("LoanAgreementRepaymentSchedule")
                .where("loanAgreementId", loanAgreementId, "scheduleRevisionNumber", fromRevision).queryList()) {
            GenericValue line = delegator.makeValue("LoanAgreementRepaymentSchedule", row.getAllFields());
            line.set("scheduleRevisionNumber", toRevision);
            line.create();
        }
    }

    /** Billing / accrual / delinquency after {@code after} (any when null). */
    private static String processedAfter(Delegator delegator, GenericValue agreement, Timestamp after)
            throws GenericEntityException {
        String id = agreement.getString("loanAgreementId");
        if (count(delegator, "LoanInstallmentInvoice", after, "loanAgreementId", id) > 0) {
            return "billing";
        }
        if (count(delegator, "LoanAccrual", after, "loanAgreementId", id) > 0) {
            return "interest accrual";
        }
        for (String e : UtilMisc.toList("LoanDelinquency", "LoanLateFeeAccrual", "LoanDunningNotice", "LoanDelinquencyAction")) {
            if (count(delegator, e, after, "financeContractId", agreement.getString("financeContractId")) > 0) {
                return "delinquency";
            }
        }
        return null;
    }

    /** True when a not reversed prepayment / modification was created after {@code after}. */
    private static boolean laterEvent(Delegator delegator, String loanAgreementId, Timestamp after) throws GenericEntityException {
        return count(delegator, "LoanPrepayment", after, "loanAgreementId", loanAgreementId, "reversedDate", null) > 0
                || count(delegator, "LoanModification", after, "loanAgreementId", loanAgreementId, "reversedDate", null) > 0;
    }

    // ------------------------------------------------------------------
    // Activation (contract creation)
    // ------------------------------------------------------------------

    public static Map<String, Object> reverseLoanAgreement(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");
        String loanAgreementId = (String) context.get("loanAgreementId");
        String reason = (String) context.get("reason");
        try {
            if (FinanceReversalHelper.reasonMissing(reason)) {
                return error("FinanceReversalReasonRequired", UtilMisc.toMap(), locale);
            }
            GenericValue agreement = FinanceAccountingServices.lockLoanAgreement(delegator, loanAgreementId);
            if (agreement == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LoanAgreement", "id", loanAgreementId), locale);
            }
            if (!"LOANAGR_ACTIVE".equals(agreement.getString("statusId"))) {
                return error("FinanceReversalNotApplicable", UtilMisc.toMap("id", loanAgreementId,
                        "statusId", agreement.getString("statusId")), locale);
            }
            String processed = processedAfter(delegator, agreement, null);
            if (processed != null) {
                return blocked(processed, locale);
            }
            if (laterEvent(delegator, loanAgreementId, null)) {
                return blocked("prepayment / change of terms", locale);
            }
            Timestamp now = UtilDateTime.nowTimestamp();
            String contractId = agreement.getString("financeContractId");
            agreement.set("statusId", "LOANAGR_CANCELLED");
            agreement.store();
            FinanceContractHelper.updateStatus(delegator, contractId, FinanceContractHelper.STATUS_CANCELLED);
            if (UtilValidate.isNotEmpty(agreement.getString("finAccountId"))) {
                GenericValue account = EntityQuery.use(delegator).from("FinAccount")
                        .where("finAccountId", agreement.getString("finAccountId")).queryOne();
                if (account != null) {
                    account.set("statusId", "FNACT_CANCELLED");
                    account.set("thruDate", now);
                    account.store();
                }
            }
            for (GenericValue lien : EntityQuery.use(delegator).from("LoanCollateral")
                    .where("loanAgreementId", loanAgreementId, "thruDate", null).queryList()) {
                lien.set("thruDate", now);
                lien.store();
            }
            // The application can be contracted again (with a new quote: one agreement per quote).
            GenericValue application = EntityQuery.use(delegator).from("LoanApplication")
                    .where("loanApplicationId", agreement.getString("loanApplicationId")).queryOne();
            if (application != null && "LOANAPP_CONTRACTED".equals(application.getString("statusId"))) {
                application.set("statusId", "LOANAPP_APPROVED");
                application.store();
                GenericValue history = delegator.makeValue("LoanApplicationStatus");
                history.set("loanApplicationId", application.getString("loanApplicationId"));
                history.set("statusId", "LOANAPP_APPROVED");
                history.set("statusDate", now);
                history.set("changeByUserLoginId", userLogin == null ? null : userLogin.getString("userLoginId"));
                history.create();
            }
            String reversalId = FinanceReversalHelper.record(delegator, contractId, FinanceReversalHelper.TYPE_LOAN_ACTIVATE,
                    loanAgreementId, agreement.getTimestamp("agreementDate"), reason, null, null, userLogin);
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("financeReversalId", reversalId);
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Prepayment
    // ------------------------------------------------------------------

    public static Map<String, Object> reverseLoanPrepayment(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");
        String loanPrepaymentId = (String) context.get("loanPrepaymentId");
        String reason = (String) context.get("reason");
        try {
            if (FinanceReversalHelper.reasonMissing(reason)) {
                return error("FinanceReversalReasonRequired", UtilMisc.toMap(), locale);
            }
            GenericValue p = EntityQuery.use(delegator).from("LoanPrepayment").where("loanPrepaymentId", loanPrepaymentId)
                    .cache(false).queryOne();
            if (p == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LoanPrepayment", "id", loanPrepaymentId), locale);
            }
            String loanAgreementId = p.getString("loanAgreementId");
            GenericValue agreement = FinanceAccountingServices.lockLoanAgreement(delegator, loanAgreementId);
            p.refresh();
            boolean full = "PREPAY_FULL".equals(p.getString("prepaymentTypeEnumId"));
            String status = agreement.getString("statusId");
            long current = FinanceAccountingServices.currentScheduleRevision(delegator, loanAgreementId);
            // The latest not reversed event only (checked below); the schedule goes back to the revision before it.
            if (p.get("reversedDate") != null || p.getLong("fromRevisionNumber") == null
                    || (full ? !"LOANAGR_CLOSED".equals(status) : !"LOANAGR_ACTIVE".equals(status))) {
                return error("FinanceReversalNotApplicable", UtilMisc.toMap("id", loanPrepaymentId, "statusId", status), locale);
            }
            Timestamp after = p.getTimestamp("createdStamp");
            if (laterEvent(delegator, loanAgreementId, after)) {
                return blocked("later prepayment / change of terms", locale);
            }
            String processed = processedAfter(delegator, agreement, after);
            if (processed != null) {
                return blocked(processed, locale);
            }
            Timestamp now = UtilDateTime.nowTimestamp();
            List<String> reversing = new ArrayList<>();
            // The settlement payment is voided (the core reverses its GL entries).
            if (UtilValidate.isNotEmpty(p.getString("paymentId"))) {
                check(dispatcher.runSync("voidPayment", UtilMisc.toMap("paymentId", p.getString("paymentId"),
                        "userLogin", userLogin)));
                FinanceGlHelper.postUnposted(delegator, dispatcher, "paymentId", p.getString("paymentId"), userLogin);
            }
            reversing.add(FinanceGlHelper.reverseTransaction(delegator, dispatcher, p.getString("feeAcctgTransId"),
                    "Reversal of loan prepayment fee " + loanPrepaymentId, now, userLogin));
            if (full) {
                agreement.set("statusId", "LOANAGR_ACTIVE");
                agreement.store();
                FinanceContractHelper.updateStatus(delegator, agreement.getString("financeContractId"),
                        FinanceContractHelper.STATUS_ACTIVE);
            } else {
                copyRevision(delegator, loanAgreementId, p.getLong("fromRevisionNumber"), current + 1);
            }
            String reversalId = FinanceReversalHelper.record(delegator, agreement.getString("financeContractId"),
                    FinanceReversalHelper.TYPE_LOAN_PREPAY, loanPrepaymentId, p.getTimestamp("prepaymentDate"), reason,
                    reversing, null, userLogin);
            p.set("reversedDate", now);
            p.set("financeReversalId", reversalId);
            p.store();
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("financeReversalId", reversalId);
            return result;
        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Change of terms
    // ------------------------------------------------------------------

    public static Map<String, Object> reverseLoanModification(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");
        String loanModificationId = (String) context.get("loanModificationId");
        String reason = (String) context.get("reason");
        try {
            if (FinanceReversalHelper.reasonMissing(reason)) {
                return error("FinanceReversalReasonRequired", UtilMisc.toMap(), locale);
            }
            GenericValue m = EntityQuery.use(delegator).from("LoanModification").where("loanModificationId", loanModificationId)
                    .cache(false).queryOne();
            if (m == null) {
                return error("FinanceRecordNotFound", UtilMisc.toMap("entityName", "LoanModification", "id", loanModificationId), locale);
            }
            String loanAgreementId = m.getString("loanAgreementId");
            GenericValue agreement = FinanceAccountingServices.lockLoanAgreement(delegator, loanAgreementId);
            m.refresh();
            long current = FinanceAccountingServices.currentScheduleRevision(delegator, loanAgreementId);
            if (m.get("reversedDate") != null || !"LOANAGR_ACTIVE".equals(agreement.getString("statusId"))
                    || m.getLong("fromRevisionNumber") == null) {
                return error("FinanceReversalNotApplicable", UtilMisc.toMap("id", loanModificationId,
                        "statusId", agreement.getString("statusId")), locale);
            }
            Timestamp after = m.getTimestamp("createdStamp");
            if (laterEvent(delegator, loanAgreementId, after)) {
                return blocked("later prepayment / change of terms", locale);
            }
            String processed = processedAfter(delegator, agreement, after);
            if (processed != null) {
                return blocked(processed, locale);
            }
            Timestamp now = UtilDateTime.nowTimestamp();
            agreement.set("annualInterestRate", m.get("oldAnnualInterestRate"));
            agreement.set("termMonths", m.get("oldTermMonths"));
            agreement.store();
            copyRevision(delegator, loanAgreementId, m.getLong("fromRevisionNumber"), current + 1);
            String reversalId = FinanceReversalHelper.record(delegator, agreement.getString("financeContractId"),
                    FinanceReversalHelper.TYPE_LOAN_MOD, loanModificationId, m.getTimestamp("effectiveDate"), reason, null,
                    null, userLogin);
            m.set("reversedDate", now);
            m.set("financeReversalId", reversalId);
            m.store();
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("financeReversalId", reversalId);
            result.put("scheduleRevisionNumber", current + 1);
            return result;
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
            return ServiceUtil.returnError(e.getMessage());
        }
    }
}
