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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Allocation of a lease payment component (STEP L9, 維持管理費用相当額) over the
 * installments. Pure calculation, no entity access.
 *
 * <p>A component (property tax equivalent, insurance, maintenance, other) is a
 * level monthly amount paid in addition to the lease payment (principal and the
 * interest equivalent). Components do not change the principal / interest math of
 * {@link LeaseCalculator}; they are additive and, when the revenue treatment is
 * "separate" (区分), billed and recognized separately from the method-1 sales.
 *
 * <p>The consumption tax of a component depends on its tax classification:
 * taxable (課税) is amount x rate with currency rounding per installment; exempt
 * (非課税), non-taxable (不課税) and zero-rated (免税) are 0.
 */
public final class LeaseComponentCalculator {

    // Tax classification (LEASE_TAX_CLASS).
    public static final String TAX_TAXABLE = "LSTAX_TAXABLE";
    public static final String TAX_EXEMPT = "LSTAX_EXEMPT";
    public static final String TAX_NONTAX = "LSTAX_NONTAX";
    public static final String TAX_ZERO = "LSTAX_ZERO";

    // Revenue treatment (LEASE_CMP_REVENUE).
    public static final String REV_SEPARATE = "LSCMP_REV_SEPARATE";
    public static final String REV_INCLUDED = "LSCMP_REV_INCLUDED";

    private LeaseComponentCalculator() {
    }

    /** True when the tax classification charges consumption tax (only 課税 does). */
    public static boolean isTaxable(String taxClassEnumId) {
        return TAX_TAXABLE.equals(taxClassEnumId);
    }

    /** Definition of one component to allocate. */
    public static final class Component {
        private final String componentTypeEnumId;
        private final BigDecimal monthlyAmount;
        private final String taxClassEnumId;
        private final String revenueTreatmentEnumId;

        public Component(String componentTypeEnumId, BigDecimal monthlyAmount, String taxClassEnumId,
                String revenueTreatmentEnumId) {
            this.componentTypeEnumId = componentTypeEnumId;
            this.monthlyAmount = monthlyAmount == null ? BigDecimal.ZERO : monthlyAmount;
            this.taxClassEnumId = taxClassEnumId == null ? TAX_TAXABLE : taxClassEnumId;
            this.revenueTreatmentEnumId = revenueTreatmentEnumId == null ? REV_SEPARATE : revenueTreatmentEnumId;
        }

        public String getComponentTypeEnumId() {
            return componentTypeEnumId;
        }

        public BigDecimal getMonthlyAmount() {
            return monthlyAmount;
        }

        public String getTaxClassEnumId() {
            return taxClassEnumId;
        }

        public String getRevenueTreatmentEnumId() {
            return revenueTreatmentEnumId;
        }
    }

    /** Per-installment amount and tax of one component. */
    public static final class Row {
        private final int installmentNumber;
        private final BigDecimal amount;
        private final BigDecimal taxAmount;

        Row(int installmentNumber, BigDecimal amount, BigDecimal taxAmount) {
            this.installmentNumber = installmentNumber;
            this.amount = amount;
            this.taxAmount = taxAmount;
        }

        public int getInstallmentNumber() {
            return installmentNumber;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public BigDecimal getTaxAmount() {
            return taxAmount;
        }
    }

    /** Allocation of one component over the term: the per-installment rows and the totals. */
    public static final class Result {
        private final List<Row> rows;
        private final BigDecimal totalAmount;
        private final BigDecimal totalTaxAmount;

        Result(List<Row> rows, BigDecimal totalAmount, BigDecimal totalTaxAmount) {
            this.rows = Collections.unmodifiableList(rows);
            this.totalAmount = totalAmount;
            this.totalTaxAmount = totalTaxAmount;
        }

        public List<Row> getRows() {
            return rows;
        }

        public BigDecimal getTotalAmount() {
            return totalAmount;
        }

        public BigDecimal getTotalTaxAmount() {
            return totalTaxAmount;
        }
    }

    /**
     * Allocate one component over {@code termMonths} installments as a level monthly
     * amount. The consumption tax of each installment is the amount x the rate when
     * the component is taxable (課税), rounded to the currency scale; otherwise 0.
     *
     * @param component  the component definition
     * @param termMonths number of installments (&gt; 0)
     * @param taxRate    the contract consumption tax rate (fraction, e.g. 0.10)
     * @param scale      currency scale
     * @param mode       currency rounding mode
     * @throws IllegalArgumentException when the monthly amount is negative or the term is not positive
     */
    public static Result allocate(Component component, int termMonths, BigDecimal taxRate, int scale,
            RoundingMode mode) {
        if (termMonths <= 0) {
            throw new IllegalArgumentException("termMonths must be greater than zero");
        }
        BigDecimal monthly = component.getMonthlyAmount();
        if (monthly.signum() < 0) {
            throw new IllegalArgumentException("component monthlyAmount must not be negative");
        }
        BigDecimal rate = isTaxable(component.getTaxClassEnumId()) && taxRate != null ? taxRate : BigDecimal.ZERO;
        BigDecimal amount = monthly.setScale(scale, mode);
        BigDecimal monthlyTax = amount.multiply(rate).setScale(scale, mode);
        List<Row> rows = new ArrayList<>(termMonths);
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalTax = BigDecimal.ZERO;
        for (int k = 1; k <= termMonths; k++) {
            rows.add(new Row(k, amount, monthlyTax));
            totalAmount = totalAmount.add(amount);
            totalTax = totalTax.add(monthlyTax);
        }
        return new Result(rows, totalAmount, totalTax);
    }
}
