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
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.entity.util.EntityUtilProperties;

/**
 * Consumption tax of lease payments (STEP L5).
 *
 * <ul>
 * <li>Rate: the core {@code TaxAuthorityRateProduct} (type SALES_TAX) of a product
 *     category the lease product belongs to, for the tax authority geo
 *     {@code lease.tax.authority.geoId}, valid at the given date (the lease start, so the
 *     rate is fixed for the whole contract). Without such a rate the rate is
 *     {@code lease.tax.rate} and no tax authority (STEP L2 / L4 behaviour).
 *     A non-taxable product has rate 0.</li>
 * <li>Timing: {@code FinancialProductLease.taxTimingEnumId}, else {@code lease.tax.timing},
 *     else {@link #TIMING_INSTALLMENT}.</li>
 * <li>Rounding: currency rounding per installment. With the lump-sum timing the
 *     contract total is rounded once and the last installment absorbs the difference.</li>
 * </ul>
 */
public final class LeaseTaxHelper {

    public static final String TIMING_INSTALLMENT = "LEASE_TAX_INSTALL";
    public static final String TIMING_AT_START = "LEASE_TAX_AT_START";

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private LeaseTaxHelper() {
    }

    /** A resolved tax rate (fraction, e.g. 0.10) and the core rate row it came from (may be null). */
    public static final class TaxRate {
        private final BigDecimal rate;
        private final String taxAuthPartyId;
        private final String taxAuthGeoId;
        private final String taxAuthorityRateSeqId;

        TaxRate(BigDecimal rate, String taxAuthPartyId, String taxAuthGeoId, String taxAuthorityRateSeqId) {
            this.rate = rate;
            this.taxAuthPartyId = taxAuthPartyId;
            this.taxAuthGeoId = taxAuthGeoId;
            this.taxAuthorityRateSeqId = taxAuthorityRateSeqId;
        }

        public BigDecimal getRate() {
            return rate;
        }

        public String getTaxAuthPartyId() {
            return taxAuthPartyId;
        }

        public String getTaxAuthGeoId() {
            return taxAuthGeoId;
        }

        public String getTaxAuthorityRateSeqId() {
            return taxAuthorityRateSeqId;
        }

        /** True when the rate comes from a core tax authority rate. */
        public boolean hasAuthority() {
            return UtilValidate.isNotEmpty(taxAuthPartyId) && UtilValidate.isNotEmpty(taxAuthGeoId);
        }
    }

    private static boolean validAt(GenericValue row, Timestamp asOf) {
        Timestamp from = row.getTimestamp("fromDate");
        Timestamp thru = row.getTimestamp("thruDate");
        return (from == null || !from.after(asOf)) && (thru == null || thru.after(asOf));
    }

    /** Tax rate of a lease product valid at {@code asOf}. */
    public static TaxRate resolveRate(Delegator delegator, String productId, Timestamp asOf)
            throws GenericEntityException {
        GenericValue terms = LeaseProductHelper.findLeaseProduct(delegator, productId);
        if (terms != null && "N".equals(terms.getString("taxable"))) {
            return new TaxRate(BigDecimal.ZERO, null, null, null);
        }
        List<String> categories = new ArrayList<>();
        for (GenericValue member : EntityQuery.use(delegator).from("ProductCategoryMember")
                .where("productId", productId).queryList()) {
            if (validAt(member, asOf)) {
                categories.add(member.getString("productCategoryId"));
            }
        }
        if (!categories.isEmpty()) {
            List<EntityCondition> conditions = new ArrayList<>();
            conditions.add(EntityCondition.makeCondition("productCategoryId", EntityOperator.IN, categories));
            conditions.add(EntityCondition.makeCondition("taxAuthorityRateTypeId", EntityOperator.EQUALS, "SALES_TAX"));
            String geoId = EntityUtilProperties.getPropertyValue("finance", "lease.tax.authority.geoId", "", delegator).trim();
            if (UtilValidate.isNotEmpty(geoId)) {
                conditions.add(EntityCondition.makeCondition("taxAuthGeoId", EntityOperator.EQUALS, geoId));
            }
            for (GenericValue row : EntityQuery.use(delegator).from("TaxAuthorityRateProduct")
                    .where(EntityCondition.makeCondition(conditions, EntityOperator.AND))
                    .orderBy("-fromDate", "taxAuthorityRateSeqId").cache(false).queryList()) {
                if (validAt(row, asOf) && row.getBigDecimal("taxPercentage") != null) {
                    BigDecimal rate = row.getBigDecimal("taxPercentage").divide(HUNDRED, 6, RoundingMode.HALF_UP);
                    return new TaxRate(rate, row.getString("taxAuthPartyId"), row.getString("taxAuthGeoId"),
                            row.getString("taxAuthorityRateSeqId"));
                }
            }
        }
        BigDecimal fallback = new BigDecimal(EntityUtilProperties.getPropertyValue("finance", "lease.tax.rate", "0.10",
                delegator).trim());
        return new TaxRate(fallback, null, null, null);
    }

    /** Tax timing of a lease product (product, then lease.tax.timing, then per installment). */
    public static String resolveTiming(Delegator delegator, String productId) throws GenericEntityException {
        GenericValue terms = LeaseProductHelper.findLeaseProduct(delegator, productId);
        if (terms != null && UtilValidate.isNotEmpty(terms.getString("taxTimingEnumId"))) {
            return terms.getString("taxTimingEnumId");
        }
        String configured = EntityUtilProperties.getPropertyValue("finance", "lease.tax.timing", TIMING_INSTALLMENT,
                delegator).trim();
        return UtilValidate.isNotEmpty(configured) ? configured : TIMING_INSTALLMENT;
    }

    /**
     * Tax of each installment. Per installment: each amount x rate with currency
     * rounding. Lump sum: the total (sum of the amounts x rate) is rounded once and
     * the last installment takes the difference, so the installments sum to it.
     */
    public static List<BigDecimal> allocate(List<BigDecimal> leaseAmounts, BigDecimal rate, boolean lumpSum,
            int scale, RoundingMode mode) {
        List<BigDecimal> taxes = new ArrayList<>(leaseAmounts.size());
        BigDecimal r = rate == null ? BigDecimal.ZERO : rate;
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal totalLease = BigDecimal.ZERO;
        for (BigDecimal amount : leaseAmounts) {
            BigDecimal tax = (amount == null ? BigDecimal.ZERO : amount).multiply(r).setScale(scale, mode);
            taxes.add(tax);
            sum = sum.add(tax);
            totalLease = totalLease.add(amount == null ? BigDecimal.ZERO : amount);
        }
        if (lumpSum && !taxes.isEmpty()) {
            BigDecimal total = totalLease.multiply(r).setScale(scale, mode);
            int last = taxes.size() - 1;
            taxes.set(last, taxes.get(last).add(total.subtract(sum)));
        }
        return taxes;
    }
}
