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

package org.apache.ofbiz.finance.lease;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
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
import org.apache.ofbiz.entity.util.EntityUtilProperties;
import org.apache.ofbiz.finance.common.DelinquencyComputation;
import org.apache.ofbiz.finance.common.FinanceContractAdapter;
import org.apache.ofbiz.finance.common.FinanceContractHelper;

/**
 * Lease-layer implementation of {@link FinanceContractAdapter} (registered in STEP L2,
 * delinquency in STEP L6).
 *
 * <p>Delinquency of a lease is derived from the current revision of the confirmed
 * lease payment schedule: every installment due by the as-of date whose invoice is
 * not settled (or that is not billed yet) is overdue by its unpaid amount. Payments
 * are allocated tax first, then interest, then principal (STEP L4), so the unpaid
 * amount of an installment is principal first, then interest, then tax. Days past
 * due are counted from the oldest overdue due date. The late fee base is the overdue
 * lease payments with or without the tax ({@code lease.latefee.base}).
 */
public final class LeaseContractAdapter implements FinanceContractAdapter {

    public static final String LATE_FEE_BASE_TAX_INCLUDED = "TAX_INCLUDED";
    public static final String LATE_FEE_BASE_TAX_EXCLUDED = "TAX_EXCLUDED";

    @Override
    public String getContractTypeId() {
        return FinanceContractHelper.TYPE_LEASE;
    }

    @Override
    public String getAgreementIdField() {
        return "leaseAgreementId";
    }

    @Override
    public String getAgreementEntityName() {
        return "LeaseAgreement";
    }

    @Override
    public String getDescriptionPrefix() {
        return "Lease";
    }

    @Override
    public String findContractIdByAgreementId(Delegator delegator, String agreementId)
            throws GenericEntityException {
        GenericValue agreement = EntityQuery.use(delegator).from("LeaseAgreement")
                .where("leaseAgreementId", agreementId).queryOne();
        return agreement == null ? null : agreement.getString("financeContractId");
    }

    @Override
    public GenericValue findAgreement(Delegator delegator, String financeContractId)
            throws GenericEntityException {
        return EntityQuery.use(delegator).from("LeaseAgreement")
                .where("financeContractId", financeContractId).cache(false).queryFirst();
    }

    @Override
    public boolean isActive(GenericValue agreement) {
        return "LEASEAGR_ACTIVE".equals(agreement.getString("statusId"));
    }

    @Override
    public String getOrganizationPartyId(Delegator delegator, GenericValue agreement) {
        return agreement.getString("organizationPartyId");
    }

    private static boolean isSettled(GenericValue invoice) {
        String status = invoice.getString("statusId");
        return "INVOICE_PAID".equals(status) || "INVOICE_WRITEOFF".equals(status)
                || "INVOICE_CANCELLED".equals(status);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** Late fee base setting: TAX_INCLUDED (default) or TAX_EXCLUDED. */
    public static boolean lateFeeIncludesTax(Delegator delegator) {
        String base = EntityUtilProperties.getPropertyValue("finance", "lease.latefee.base",
                LATE_FEE_BASE_TAX_INCLUDED, delegator).trim();
        return !LATE_FEE_BASE_TAX_EXCLUDED.equals(base);
    }

    /** Overdue installments of the agreement as of a date (unpaid split into principal / interest / tax). */
    public static List<Map<String, Object>> overdueInstallments(Delegator delegator, GenericValue agreement,
            Timestamp asOfDate) throws GenericEntityException {
        String leaseAgreementId = agreement.getString("leaseAgreementId");
        List<Map<String, Object>> rows = new ArrayList<>();
        List<GenericValue> schedule = EntityQuery.use(delegator).from("LeaseAgreementPaymentSchedule")
                .where("leaseAgreementId", leaseAgreementId,
                        "scheduleRevisionNumber", LeaseAccountingServices.currentScheduleRevision(delegator, leaseAgreementId))
                .orderBy("dueDate", "installmentNumber").queryList();
        for (GenericValue row : schedule) {
            Timestamp dueDate = row.getTimestamp("dueDate");
            if (dueDate == null || dueDate.after(asOfDate)) {
                continue;
            }
            BigDecimal principal = nz(row.getBigDecimal("principalPortion"));
            BigDecimal interest = nz(row.getBigDecimal("interestPortion"));
            BigDecimal tax = nz(row.getBigDecimal("taxAmount"));
            BigDecimal component = BigDecimal.ZERO;
            BigDecimal componentTax = BigDecimal.ZERO;
            BigDecimal unpaid = nz(row.getBigDecimal("leaseAmount")).add(tax);
            GenericValue link = EntityQuery.use(delegator).from("LeaseInstallmentInvoice")
                    .where("leaseAgreementId", leaseAgreementId, "installmentNumber", row.getLong("installmentNumber"))
                    .queryOne();
            String invoiceId = null;
            if (link != null) {
                // The billed amounts are those of the invoice (the revision billed).
                principal = nz(link.getBigDecimal("principalPortion"));
                interest = nz(link.getBigDecimal("interestPortion"));
                tax = nz(link.getBigDecimal("taxAmount"));
                component = nz(link.getBigDecimal("componentAmount"));
                componentTax = nz(link.getBigDecimal("componentTaxAmount"));
                invoiceId = link.getString("invoiceId");
                GenericValue invoice = EntityQuery.use(delegator).from("Invoice").where("invoiceId", invoiceId)
                        .cache(false).queryOne();
                if (invoice == null || isSettled(invoice)) {
                    continue;
                }
                unpaid = nz(InvoiceWorker.getInvoiceNotApplied(invoice));
            } else {
                // Not billed yet: include the scheduled component amounts of the installment.
                for (GenericValue comp : EntityQuery.use(delegator).from("LeaseAgrPaySchedComp")
                        .where("leaseAgreementId", leaseAgreementId,
                                "scheduleRevisionNumber", LeaseAccountingServices.currentScheduleRevision(delegator, leaseAgreementId),
                                "installmentNumber", row.getLong("installmentNumber")).queryList()) {
                    component = component.add(nz(comp.getBigDecimal("amount")));
                    componentTax = componentTax.add(nz(comp.getBigDecimal("taxAmount")));
                }
                unpaid = unpaid.add(component).add(componentTax);
            }
            if (unpaid.signum() <= 0) {
                continue;
            }
            // Unpaid remains in the order principal, interest, component, tax (component tax
            // merged with the consumption tax). The late fee base uses the lease payment
            // (principal + interest), optionally including the tax; components stay separate.
            BigDecimal unpaidPrincipal = unpaid.min(principal).max(BigDecimal.ZERO);
            BigDecimal afterPrincipal = unpaid.subtract(unpaidPrincipal);
            BigDecimal unpaidInterest = afterPrincipal.min(interest).max(BigDecimal.ZERO);
            BigDecimal afterInterest = afterPrincipal.subtract(unpaidInterest);
            BigDecimal unpaidComponent = afterInterest.min(component).max(BigDecimal.ZERO);
            BigDecimal unpaidTax = afterInterest.subtract(unpaidComponent).max(BigDecimal.ZERO);
            rows.add(UtilMisc.toMap("installmentNumber", row.getLong("installmentNumber"), "dueDate", dueDate,
                    "invoiceId", invoiceId, "unpaidAmount", unpaid, "principalAmount", unpaidPrincipal,
                    "interestAmount", unpaidInterest, "componentAmount", unpaidComponent, "taxAmount", unpaidTax,
                    "leaseAmount", unpaidPrincipal.add(unpaidInterest)));
        }
        return rows;
    }

    @Override
    public DelinquencyComputation computeDelinquency(Delegator delegator, GenericValue agreement,
            Timestamp asOfDate) throws GenericEntityException {
        BigDecimal principal = BigDecimal.ZERO;
        BigDecimal interest = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal lateFeeTax = BigDecimal.ZERO;
        Timestamp oldest = null;
        for (Map<String, Object> row : overdueInstallments(delegator, agreement, asOfDate)) {
            principal = principal.add((BigDecimal) row.get("principalAmount"));
            interest = interest.add((BigDecimal) row.get("interestAmount"));
            lateFeeTax = lateFeeTax.add((BigDecimal) row.get("taxAmount"));
            // The component charge is overdue too; it is grouped with the tax bucket
            // (both are credited to accounts receivable on write-off) so the total
            // delinquency covers the whole unpaid invoice. It does not inflate the
            // late fee base (the base is the lease payment, optionally with its tax).
            tax = tax.add((BigDecimal) row.get("taxAmount")).add((BigDecimal) row.get("componentAmount"));
            if (oldest == null) {
                oldest = (Timestamp) row.get("dueDate");
            }
        }
        long daysPastDue = 0;
        if (oldest != null) {
            LocalDate due = oldest.toLocalDateTime().toLocalDate();
            LocalDate asOf = asOfDate.toLocalDateTime().toLocalDate();
            daysPastDue = Math.max(ChronoUnit.DAYS.between(due, asOf), 0L);
        }
        BigDecimal base = lateFeeIncludesTax(delegator) ? principal.add(interest).add(lateFeeTax) : principal.add(interest);
        return new DelinquencyComputation(daysPastDue, principal, interest, tax, base);
    }

    @Override
    public List<String> findOpenInvoiceIds(Delegator delegator, GenericValue agreement)
            throws GenericEntityException {
        List<String> invoiceIds = new ArrayList<>();
        for (GenericValue link : EntityQuery.use(delegator).from("LeaseInstallmentInvoice")
                .where("leaseAgreementId", agreement.getString("leaseAgreementId"))
                .orderBy("installmentNumber").queryList()) {
            GenericValue invoice = EntityQuery.use(delegator).from("Invoice")
                    .where("invoiceId", link.getString("invoiceId")).cache(false).queryOne();
            if (invoice != null && !isSettled(invoice)) {
                invoiceIds.add(link.getString("invoiceId"));
            }
        }
        return invoiceIds;
    }

    @Override
    public Map<String, Object> getAgreementKeyFields(GenericValue agreement) {
        // Delinquency records carry financeContractId only; there is no lease key column.
        return UtilMisc.toMap();
    }

    /** Every component of a lease invoice (payment and tax) is in accounts receivable. */
    @Override
    public String getWriteOffCreditAccountType(String component) {
        return "ACCOUNTS_RECEIVABLE";
    }

    /** STEP L10a: the contract customer of a lease is the lessee. */
    @Override
    public String getCustomerRoleTypeId() {
        return "FIN_LESSEE";
    }

    /** STEP L10a: the accounting organization of a lease is the lessor. */
    @Override
    public String getOrganizationRoleTypeId() {
        return "FIN_LESSOR";
    }
}
