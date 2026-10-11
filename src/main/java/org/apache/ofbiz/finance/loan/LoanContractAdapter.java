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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.accounting.invoice.InvoiceWorker;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.finance.common.DelinquencyComputation;
import org.apache.ofbiz.finance.common.FinanceContractAdapter;
import org.apache.ofbiz.finance.common.FinanceContractHelper;

/**
 * Loan-layer implementation of {@link FinanceContractAdapter} (STEP L0).
 * Delinquency of a loan is derived from the current revision of the confirmed
 * repayment schedule and the open amount of its installment invoices.
 */
public final class LoanContractAdapter implements FinanceContractAdapter {

    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    @Override
    public String getContractTypeId() {
        return FinanceContractHelper.TYPE_LOAN;
    }

    @Override
    public String getAgreementIdField() {
        return "loanAgreementId";
    }

    @Override
    public String getAgreementEntityName() {
        return "LoanAgreement";
    }

    @Override
    public String getDescriptionPrefix() {
        return "Loan";
    }

    @Override
    public String findContractIdByAgreementId(Delegator delegator, String agreementId)
            throws GenericEntityException {
        GenericValue agreement = EntityQuery.use(delegator).from("LoanAgreement")
                .where("loanAgreementId", agreementId).queryOne();
        return agreement == null ? null : agreement.getString("financeContractId");
    }

    @Override
    public GenericValue findAgreement(Delegator delegator, String financeContractId)
            throws GenericEntityException {
        return EntityQuery.use(delegator).from("LoanAgreement")
                .where("financeContractId", financeContractId).cache(false).queryFirst();
    }

    @Override
    public boolean isActive(GenericValue agreement) {
        return "LOANAGR_ACTIVE".equals(agreement.getString("statusId"));
    }

    @Override
    public String getOrganizationPartyId(Delegator delegator, GenericValue agreement)
            throws GenericEntityException {
        GenericValue finAccount = agreement.getRelatedOne("FinAccount", false);
        return finAccount == null ? null : finAccount.getString("organizationPartyId");
    }

    @Override
    public DelinquencyComputation computeDelinquency(Delegator delegator, GenericValue agreement,
            Timestamp asOfDate) throws GenericEntityException {
        String loanAgreementId = agreement.getString("loanAgreementId");
        BigDecimal overduePrincipal = BigDecimal.ZERO;
        BigDecimal overdueInterest = BigDecimal.ZERO;
        Timestamp oldestDueDate = null;

        List<GenericValue> schedule = EntityQuery.use(delegator)
                .from("LoanAgreementRepaymentSchedule")
                .where("loanAgreementId", loanAgreementId,
                        "scheduleRevisionNumber",
                        FinanceAccountingServices.currentScheduleRevision(delegator, loanAgreementId))
                .orderBy("dueDate", "installmentNumber").queryList();
        for (GenericValue installment : schedule) {
            Timestamp dueDate = installment.getTimestamp("dueDate");
            if (dueDate == null || dueDate.after(asOfDate)) {
                continue;
            }
            BigDecimal scheduledPrincipal = defaultZero(installment.getBigDecimal("principalAmount"));
            BigDecimal scheduledInterest = defaultZero(installment.getBigDecimal("interestAmount"));
            BigDecimal overdueForInstallment = scheduledPrincipal.add(scheduledInterest);

            GenericValue link = EntityQuery.use(delegator).from("LoanInstallmentInvoice")
                    .where("loanAgreementId", loanAgreementId,
                            "installmentNumber", installment.getLong("installmentNumber")).queryOne();
            if (link != null && link.getString("invoiceId") != null) {
                GenericValue invoice = EntityQuery.use(delegator).from("Invoice")
                        .where("invoiceId", link.getString("invoiceId")).queryOne();
                if (invoice != null && ("INVOICE_PAID".equals(invoice.getString("statusId"))
                        || "INVOICE_WRITEOFF".equals(invoice.getString("statusId"))
                        || "INVOICE_CANCELLED".equals(invoice.getString("statusId")))) {
                    continue;
                }
                overdueForInstallment = defaultZero(InvoiceWorker.getInvoiceNotApplied(invoice));
            }
            BigDecimal unappliedInterest = overdueForInstallment.min(scheduledInterest);
            BigDecimal unappliedPrincipal = overdueForInstallment.subtract(unappliedInterest)
                    .min(scheduledPrincipal).max(BigDecimal.ZERO);
            overdueInterest = overdueInterest.add(unappliedInterest);
            overduePrincipal = overduePrincipal.add(unappliedPrincipal);
            if (oldestDueDate == null) {
                oldestDueDate = dueDate;
            }
        }

        long daysPastDue = 0;
        if (oldestDueDate != null) {
            LocalDate dueDay = oldestDueDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            LocalDate asOfDay = asOfDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            daysPastDue = Math.max(ChronoUnit.DAYS.between(dueDay, asOfDay), 0L);
        }
        return new DelinquencyComputation(daysPastDue,
                overduePrincipal.setScale(SCALE, ROUNDING),
                overdueInterest.setScale(SCALE, ROUNDING));
    }

    @Override
    public List<String> findOpenInvoiceIds(Delegator delegator, GenericValue agreement)
            throws GenericEntityException {
        List<String> invoiceIds = new ArrayList<>();
        for (GenericValue link : EntityQuery.use(delegator).from("LoanInstallmentInvoice")
                .where("loanAgreementId", agreement.getString("loanAgreementId")).queryList()) {
            String invoiceId = link.getString("invoiceId");
            if (invoiceId == null) {
                continue;
            }
            GenericValue invoice = EntityQuery.use(delegator).from("Invoice")
                    .where("invoiceId", invoiceId).queryOne();
            if (invoice == null || "INVOICE_PAID".equals(invoice.getString("statusId"))
                    || "INVOICE_WRITEOFF".equals(invoice.getString("statusId"))) {
                continue;
            }
            invoiceIds.add(invoiceId);
        }
        return invoiceIds;
    }

    @Override
    public Map<String, Object> getAgreementKeyFields(GenericValue agreement) {
        return UtilMisc.toMap("loanAgreementId", agreement.getString("loanAgreementId"));
    }

    private static BigDecimal defaultZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
