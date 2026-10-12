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
 * Lease payment calculation (STEP L2). Pure calculation, no entity access.
 *
 * <p>Two methods:
 * <ul>
 * <li>Rate factor: monthly lease amount = acquisition cost x monthly rate factor
 *     (currency rounding). The lessor's calculation interest rate is solved so that
 *     the present value of the lease payments and the residual value equals the
 *     acquisition cost.</li>
 * <li>Interest rate: the monthly lease amount is the annuity that recovers
 *     acquisition cost minus the present value of the residual value at the given
 *     annual calculation interest rate (rounded up to the currency scale so that
 *     the recovery is never short).</li>
 * </ul>
 * Each installment is split with the interest method into an interest portion
 * (on the outstanding principal at the monthly rate) and a principal recovery.
 * With payments in advance the first installment has no interest. The last
 * installment absorbs rounding so that the principal recoveries sum to
 * acquisition cost minus residual value and the remaining principal ends at the
 * residual value.
 */
public final class LeaseCalculator {

    public static final String METHOD_RATE_FACTOR = "LEASE_CALC_RATE_FCTR";
    public static final String METHOD_INTEREST_RATE = "LEASE_CALC_INT_RATE";

    private static final int RATE_SCALE = 10;
    private static final int STORED_RATE_SCALE = 6;
    private static final BigDecimal TWELVE = new BigDecimal("12");

    private LeaseCalculator() {
    }

    /** Calculation input. Exactly one of monthlyRateFactor / annualCalcRate drives the calculation. */
    public static final class Input {
        private BigDecimal acquisitionCost;
        private BigDecimal residualValue = BigDecimal.ZERO;
        private int termMonths;
        private boolean advance = true;
        private BigDecimal monthlyRateFactor;
        private BigDecimal annualCalcRate;
        private BigDecimal taxRate = BigDecimal.ZERO;
        private int scale = 2;
        private RoundingMode roundingMode = RoundingMode.HALF_UP;

        public Input acquisitionCost(BigDecimal v) {
            this.acquisitionCost = v;
            return this;
        }
        public Input residualValue(BigDecimal v) {
            this.residualValue = v == null ? BigDecimal.ZERO : v;
            return this;
        }
        public Input termMonths(int v) {
            this.termMonths = v;
            return this;
        }
        public Input advance(boolean v) {
            this.advance = v;
            return this;
        }
        public Input monthlyRateFactor(BigDecimal v) {
            this.monthlyRateFactor = v;
            return this;
        }
        public Input annualCalcRate(BigDecimal v) {
            this.annualCalcRate = v;
            return this;
        }
        public Input taxRate(BigDecimal v) {
            this.taxRate = v == null ? BigDecimal.ZERO : v;
            return this;
        }
        public Input currencyRounding(int scale, RoundingMode mode) {
            this.scale = scale;
            this.roundingMode = mode == null ? RoundingMode.HALF_UP : mode;
            return this;
        }
    }

    /** One installment of the lease payment schedule. */
    public static final class Row {
        private final int installmentNumber;
        private final BigDecimal leaseAmount;
        private final BigDecimal principalPortion;
        private final BigDecimal interestPortion;
        private final BigDecimal taxAmount;
        private final BigDecimal remainingPrincipal;

        Row(int n, BigDecimal lease, BigDecimal principal, BigDecimal interest, BigDecimal tax, BigDecimal remaining) {
            this.installmentNumber = n;
            this.leaseAmount = lease;
            this.principalPortion = principal;
            this.interestPortion = interest;
            this.taxAmount = tax;
            this.remainingPrincipal = remaining;
        }

        public int getInstallmentNumber() {
            return installmentNumber;
        }
        public BigDecimal getLeaseAmount() {
            return leaseAmount;
        }
        public BigDecimal getPrincipalPortion() {
            return principalPortion;
        }
        public BigDecimal getInterestPortion() {
            return interestPortion;
        }
        public BigDecimal getTaxAmount() {
            return taxAmount;
        }
        public BigDecimal getRemainingPrincipal() {
            return remainingPrincipal;
        }
    }

    /** Calculation result. */
    public static final class Result {
        private final String calcMethod;
        private final BigDecimal monthlyLeaseAmount;
        private final BigDecimal monthlyRateFactor;
        private final BigDecimal calcInterestRate;
        private final BigDecimal totalLeaseAmount;
        private final BigDecimal totalInterestAmount;
        private final BigDecimal totalTaxAmount;
        private final List<Row> rows;

        Result(String method, BigDecimal monthly, BigDecimal factor, BigDecimal rate, BigDecimal totalLease,
                BigDecimal totalInterest, BigDecimal totalTax, List<Row> rows) {
            this.calcMethod = method;
            this.monthlyLeaseAmount = monthly;
            this.monthlyRateFactor = factor;
            this.calcInterestRate = rate;
            this.totalLeaseAmount = totalLease;
            this.totalInterestAmount = totalInterest;
            this.totalTaxAmount = totalTax;
            this.rows = Collections.unmodifiableList(rows);
        }

        public String getCalcMethod() {
            return calcMethod;
        }
        public BigDecimal getMonthlyLeaseAmount() {
            return monthlyLeaseAmount;
        }
        /** Monthly lease amount / acquisition cost (scale 6). */
        public BigDecimal getMonthlyRateFactor() {
            return monthlyRateFactor;
        }
        /** Annual nominal calculation interest rate (12 x monthly rate, scale 6). */
        public BigDecimal getCalcInterestRate() {
            return calcInterestRate;
        }
        public BigDecimal getTotalLeaseAmount() {
            return totalLeaseAmount;
        }
        public BigDecimal getTotalInterestAmount() {
            return totalInterestAmount;
        }
        public BigDecimal getTotalTaxAmount() {
            return totalTaxAmount;
        }
        public List<Row> getRows() {
            return rows;
        }
    }

    /**
     * Calculate the lease payment schedule.
     *
     * @throws IllegalArgumentException when the input is incomplete or inconsistent
     *         (e.g. a rate factor too low to recover the acquisition cost)
     */
    public static Result calculate(Input in) {
        BigDecimal cost = in.acquisitionCost;
        BigDecimal residual = in.residualValue;
        int n = in.termMonths;
        if (cost == null || cost.signum() <= 0) {
            throw new IllegalArgumentException("acquisitionCost must be greater than zero");
        }
        if (n <= 0) {
            throw new IllegalArgumentException("termMonths must be greater than zero");
        }
        if (residual.signum() < 0 || residual.compareTo(cost) >= 0) {
            throw new IllegalArgumentException("residualValue must be zero or more and less than acquisitionCost");
        }
        if (in.taxRate.signum() < 0) {
            throw new IllegalArgumentException("taxRate must not be negative");
        }
        boolean byFactor = in.monthlyRateFactor != null;
        if (!byFactor && in.annualCalcRate == null) {
            throw new IllegalArgumentException("monthlyRateFactor or annualCalcRate is required");
        }

        BigDecimal payment;
        BigDecimal monthlyRate;
        if (byFactor) {
            if (in.monthlyRateFactor.signum() <= 0) {
                throw new IllegalArgumentException("monthlyRateFactor must be greater than zero");
            }
            payment = cost.multiply(in.monthlyRateFactor).setScale(in.scale, in.roundingMode);
            BigDecimal recovered = payment.multiply(BigDecimal.valueOf(n)).add(residual);
            if (recovered.compareTo(cost) < 0) {
                throw new IllegalArgumentException("monthlyRateFactor is too low to recover the acquisition cost");
            }
            monthlyRate = solveMonthlyRate(cost.doubleValue(), residual.doubleValue(), payment.doubleValue(),
                    n, in.advance);
        } else {
            if (in.annualCalcRate.signum() < 0) {
                throw new IllegalArgumentException("annualCalcRate must not be negative");
            }
            monthlyRate = in.annualCalcRate.divide(TWELVE, RATE_SCALE, RoundingMode.HALF_UP);
            payment = annuityPayment(cost, residual, monthlyRate, n, in.advance)
                    .setScale(in.scale, RoundingMode.UP);
        }

        List<Row> rows = new ArrayList<>(n);
        BigDecimal balance = cost;
        BigDecimal totalInterest = BigDecimal.ZERO;
        BigDecimal totalTax = BigDecimal.ZERO;
        BigDecimal tax = payment.multiply(in.taxRate).setScale(in.scale, in.roundingMode);
        for (int k = 1; k <= n; k++) {
            BigDecimal interest;
            BigDecimal principal;
            if (k == n) {
                principal = balance.subtract(residual);
                interest = payment.subtract(principal);
            } else {
                interest = (k == 1 && in.advance) ? BigDecimal.ZERO.setScale(in.scale)
                        : balance.multiply(monthlyRate).setScale(in.scale, in.roundingMode);
                principal = payment.subtract(interest);
            }
            balance = balance.subtract(principal);
            totalInterest = totalInterest.add(interest);
            totalTax = totalTax.add(tax);
            rows.add(new Row(k, payment, principal, interest, tax, balance));
        }

        BigDecimal factor = payment.divide(cost, STORED_RATE_SCALE, RoundingMode.HALF_UP);
        BigDecimal annualRate = byFactor
                ? monthlyRate.multiply(TWELVE).setScale(STORED_RATE_SCALE, RoundingMode.HALF_UP)
                : in.annualCalcRate.setScale(STORED_RATE_SCALE, RoundingMode.HALF_UP);
        return new Result(byFactor ? METHOD_RATE_FACTOR : METHOD_INTEREST_RATE, payment, factor, annualRate,
                payment.multiply(BigDecimal.valueOf(n)), totalInterest, totalTax, rows);
    }

    /** Level payment recovering cost minus PV(residual) at the monthly rate. */
    static BigDecimal annuityPayment(BigDecimal cost, BigDecimal residual, BigDecimal monthlyRate, int n,
            boolean advance) {
        if (monthlyRate.signum() == 0) {
            return cost.subtract(residual).divide(BigDecimal.valueOf(n), RATE_SCALE, RoundingMode.HALF_UP);
        }
        double j = monthlyRate.doubleValue();
        double v = 1.0 / (1.0 + j);
        double vn = Math.pow(v, n);
        double annuity = (1.0 - vn) / j;               // payments at 1..n
        if (advance) {
            annuity = annuity * (1.0 + j);              // payments at 0..n-1
        }
        double pmt = (cost.doubleValue() - residual.doubleValue() * vn) / annuity;
        return new BigDecimal(pmt).setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    /** Monthly rate j >= 0 with PV(payments) + PV(residual) = cost (bisection). */
    static BigDecimal solveMonthlyRate(double cost, double residual, double payment, int n, boolean advance) {
        if (payment * n + residual <= cost) {
            return BigDecimal.ZERO.setScale(RATE_SCALE);
        }
        double lo = 0.0;
        double hi = 1.0;
        while (presentValue(hi, residual, payment, n, advance) > cost && hi < 1e6) {
            hi *= 2;
        }
        for (int i = 0; i < 200; i++) {
            double mid = (lo + hi) / 2;
            if (presentValue(mid, residual, payment, n, advance) > cost) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return new BigDecimal((lo + hi) / 2).setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    private static double presentValue(double j, double residual, double payment, int n, boolean advance) {
        double v = 1.0 / (1.0 + j);
        double pv = 0.0;
        double factor = advance ? 1.0 : v;
        for (int k = 0; k < n; k++) {
            pv += payment * factor;
            factor *= v;
        }
        return pv + residual * Math.pow(v, n);
    }
}
