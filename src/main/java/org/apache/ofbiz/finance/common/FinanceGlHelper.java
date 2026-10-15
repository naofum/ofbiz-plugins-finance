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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Common GL helper (STEP L3): resolves GL accounts by account type and posts a
 * balanced multi-line accounting transaction through the core services
 * {@code createAcctgTransAndEntries} and {@code postAcctgTrans}.
 *
 * <p>Amounts are given in the contract currency. When it differs from the base
 * currency of the organization, each line is converted with {@code convertUom}
 * at the transaction date and rounded to 2 decimals; a rounding difference is
 * absorbed by the largest credit line so the transaction stays balanced.
 */
public final class FinanceGlHelper {

    /** One line of a transaction to post. */
    public static final class Line {
        private final String glAccountTypeId;
        private final boolean debit;
        private final BigDecimal amount;

        public Line(String glAccountTypeId, boolean debit, BigDecimal amount) {
            this.glAccountTypeId = glAccountTypeId;
            this.debit = debit;
            this.amount = amount;
        }

        public String getGlAccountTypeId() {
            return glAccountTypeId;
        }

        public boolean isDebit() {
            return debit;
        }

        public BigDecimal getAmount() {
            return amount;
        }
    }

    private FinanceGlHelper() {
    }

    /**
     * GL account of an account type for an organization: the organization's
     * {@code GlAccountTypeDefault}, else the first account of the type assigned to
     * the organization ({@code GlAccountOrganization}); null when none.
     */
    public static String findGlAccountId(Delegator delegator, String organizationPartyId, String glAccountTypeId)
            throws GenericEntityException {
        if (UtilValidate.isEmpty(organizationPartyId)) {
            return null;
        }
        GenericValue accountDefault = EntityQuery.use(delegator).from("GlAccountTypeDefault")
                .where("organizationPartyId", organizationPartyId, "glAccountTypeId", glAccountTypeId).queryFirst();
        if (accountDefault != null) {
            return accountDefault.getString("glAccountId");
        }
        for (GenericValue account : EntityQuery.use(delegator).from("GlAccount")
                .where("glAccountTypeId", glAccountTypeId).orderBy("glAccountId").queryList()) {
            if (EntityQuery.use(delegator).from("GlAccountOrganization")
                    .where("organizationPartyId", organizationPartyId, "glAccountId", account.getString("glAccountId"))
                    .queryFirst() != null) {
                return account.getString("glAccountId");
            }
        }
        return null;
    }

    /** Base currency of the organization (PartyAcctgPreference), or null. */
    public static String baseCurrency(Delegator delegator, String organizationPartyId) throws GenericEntityException {
        GenericValue pref = EntityQuery.use(delegator).from("PartyAcctgPreference")
                .where("partyId", organizationPartyId).cache().queryOne();
        return pref == null ? null : pref.getString("baseCurrencyUomId");
    }

    /**
     * Create and post one balanced accounting transaction. Zero lines are skipped.
     *
     * @return the acctgTransId
     * @throws GenericServiceException when a GL mapping is missing, a currency
     *         conversion is not available, the lines do not balance or posting fails
     */
    public static String postTransaction(Delegator delegator, LocalDispatcher dispatcher, String description,
            Timestamp transactionDate, String organizationPartyId, String partyId, String currencyUomId,
            List<Line> lines, GenericValue userLogin) throws GenericEntityException, GenericServiceException {
        String baseCurrency = baseCurrency(delegator, organizationPartyId);
        if (baseCurrency == null) {
            baseCurrency = currencyUomId;
        }
        boolean convert = !baseCurrency.equals(currencyUomId);

        List<GenericValue> entries = new ArrayList<>();
        BigDecimal debitTotal = BigDecimal.ZERO;
        BigDecimal creditTotal = BigDecimal.ZERO;
        BigDecimal origDebit = BigDecimal.ZERO;
        BigDecimal origCredit = BigDecimal.ZERO;
        GenericValue largestCredit = null;
        for (Line line : lines) {
            if (line.amount == null || line.amount.signum() == 0) {
                continue;
            }
            String glAccountId = findGlAccountId(delegator, organizationPartyId, line.glAccountTypeId);
            if (glAccountId == null) {
                throw new GenericServiceException("No GL account of type " + line.glAccountTypeId
                        + " is configured for organization " + organizationPartyId);
            }
            BigDecimal amount = line.amount;
            if (convert) {
                Map<String, Object> converted = dispatcher.runSync("convertUom", UtilMisc.toMap(
                        "uomId", currencyUomId, "uomIdTo", baseCurrency, "originalValue", line.amount,
                        "asOfDate", transactionDate));
                if (ServiceUtil.isError(converted) || converted.get("convertedValue") == null) {
                    throw new GenericServiceException("No currency conversion from " + currencyUomId + " to "
                            + baseCurrency + " as of " + transactionDate);
                }
                amount = ((BigDecimal) converted.get("convertedValue")).setScale(2, RoundingMode.HALF_UP);
            }
            GenericValue entry = delegator.makeValue("AcctgTransEntry");
            entry.set("acctgTransEntryTypeId", "_NA_");
            entry.set("glAccountId", glAccountId);
            entry.set("glAccountTypeId", line.glAccountTypeId);
            entry.set("debitCreditFlag", line.debit ? "D" : "C");
            entry.set("amount", amount);
            entry.set("origAmount", line.amount);
            entry.set("currencyUomId", baseCurrency);
            entry.set("origCurrencyUomId", currencyUomId);
            entry.set("organizationPartyId", organizationPartyId);
            entry.set("partyId", partyId);
            entry.set("description", description);
            entries.add(entry);
            if (line.debit) {
                debitTotal = debitTotal.add(amount);
                origDebit = origDebit.add(line.amount);
            } else {
                creditTotal = creditTotal.add(amount);
                origCredit = origCredit.add(line.amount);
                if (largestCredit == null || amount.compareTo(largestCredit.getBigDecimal("amount")) > 0) {
                    largestCredit = entry;
                }
            }
        }
        if (entries.isEmpty()) {
            return null;
        }
        if (origDebit.compareTo(origCredit) != 0) {
            throw new GenericServiceException("Unbalanced transaction: debit " + origDebit + " / credit " + origCredit);
        }
        BigDecimal diff = debitTotal.subtract(creditTotal);
        if (diff.signum() != 0 && largestCredit != null) {
            largestCredit.set("amount", largestCredit.getBigDecimal("amount").add(diff));
        }

        Map<String, Object> input = new HashMap<>();
        input.put("acctgTransTypeId", "OTHER_INTERNAL");
        input.put("glFiscalTypeId", "ACTUAL");
        input.put("description", description);
        input.put("transactionDate", transactionDate);
        input.put("organizationPartyId", organizationPartyId);
        input.put("partyId", partyId);
        input.put("isPosted", "N");
        input.put("acctgTransEntries", entries);
        input.put("userLogin", userLogin);
        Map<String, Object> created = dispatcher.runSync("createAcctgTransAndEntries", input);
        if (ServiceUtil.isError(created)) {
            throw new GenericServiceException(ServiceUtil.getErrorMessage(created));
        }
        String acctgTransId = (String) created.get("acctgTransId");
        if (acctgTransId == null) {
            throw new GenericServiceException("Accounting transaction was not created for organization "
                    + organizationPartyId + " (internal organization / accounting preference)");
        }
        // createAcctgTransAndEntries posts the transaction itself; post only when it did not.
        GenericValue trans = EntityQuery.use(delegator).from("AcctgTrans").where("acctgTransId", acctgTransId)
                .cache(false).queryOne();
        if (trans != null && !"Y".equals(trans.getString("isPosted"))) {
            Map<String, Object> posted = dispatcher.runSync("postAcctgTrans",
                    UtilMisc.toMap("acctgTransId", acctgTransId, "userLogin", userLogin));
            if (ServiceUtil.isError(posted) || ServiceUtil.isFailure(posted)) {
                throw new GenericServiceException(ServiceUtil.getErrorMessage(posted));
            }
        }
        return acctgTransId;
    }

    /**
     * STEP L15a: post the reversing transaction of an accounting transaction: the same
     * GL accounts and amounts with debit and credit swapped (the original stays).
     *
     * @return the reversing acctgTransId, or null when the source is empty / missing
     */
    public static String reverseTransaction(Delegator delegator, LocalDispatcher dispatcher, String acctgTransId,
            String description, Timestamp transactionDate, GenericValue userLogin)
            throws GenericEntityException, GenericServiceException {
        if (UtilValidate.isEmpty(acctgTransId)) {
            return null;
        }
        GenericValue source = EntityQuery.use(delegator).from("AcctgTrans").where("acctgTransId", acctgTransId)
                .cache(false).queryOne();
        if (source == null) {
            return null;
        }
        List<GenericValue> entries = new ArrayList<>();
        for (GenericValue e : EntityQuery.use(delegator).from("AcctgTransEntry").where("acctgTransId", acctgTransId)
                .cache(false).queryList()) {
            GenericValue entry = delegator.makeValue("AcctgTransEntry");
            for (String f : UtilMisc.toList("acctgTransEntryTypeId", "glAccountId", "glAccountTypeId", "amount",
                    "origAmount", "currencyUomId", "origCurrencyUomId", "organizationPartyId", "partyId")) {
                entry.set(f, e.get(f));
            }
            entry.set("debitCreditFlag", "D".equals(e.getString("debitCreditFlag")) ? "C" : "D");
            entry.set("description", description);
            entries.add(entry);
        }
        if (entries.isEmpty()) {
            return null;
        }
        Map<String, Object> input = new HashMap<>();
        input.put("acctgTransTypeId", "OTHER_INTERNAL");
        input.put("glFiscalTypeId", source.get("glFiscalTypeId") == null ? "ACTUAL" : source.get("glFiscalTypeId"));
        input.put("description", description);
        input.put("transactionDate", transactionDate);
        input.put("organizationPartyId", entries.get(0).get("organizationPartyId"));
        input.put("partyId", source.get("partyId"));
        input.put("isPosted", "N");
        input.put("acctgTransEntries", entries);
        input.put("userLogin", userLogin);
        Map<String, Object> created = dispatcher.runSync("createAcctgTransAndEntries", input);
        if (ServiceUtil.isError(created)) {
            throw new GenericServiceException(ServiceUtil.getErrorMessage(created));
        }
        String reversingId = (String) created.get("acctgTransId");
        GenericValue trans = EntityQuery.use(delegator).from("AcctgTrans").where("acctgTransId", reversingId)
                .cache(false).queryOne();
        if (trans != null && !"Y".equals(trans.getString("isPosted"))) {
            Map<String, Object> posted = dispatcher.runSync("postAcctgTrans",
                    UtilMisc.toMap("acctgTransId", reversingId, "userLogin", userLogin));
            if (ServiceUtil.isError(posted) || ServiceUtil.isFailure(posted)) {
                throw new GenericServiceException(ServiceUtil.getErrorMessage(posted));
            }
        }
        return reversingId;
    }

    /** Post every unposted AcctgTrans that references the given field (e.g. invoiceId / paymentId). */
    public static void postUnposted(Delegator delegator, LocalDispatcher dispatcher, String fieldName, String value,
            GenericValue userLogin) throws GenericEntityException, GenericServiceException {
        for (GenericValue trans : EntityQuery.use(delegator).from("AcctgTrans").where(fieldName, value)
                .cache(false).queryList()) {
            if (!"Y".equals(trans.getString("isPosted"))) {
                Map<String, Object> result = dispatcher.runSync("postAcctgTrans",
                        UtilMisc.toMap("acctgTransId", trans.getString("acctgTransId"), "userLogin", userLogin));
                if (ServiceUtil.isError(result)) {
                    throw new GenericServiceException(ServiceUtil.getErrorMessage(result));
                }
            }
        }
    }
}
