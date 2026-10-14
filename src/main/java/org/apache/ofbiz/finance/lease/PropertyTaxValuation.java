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

/**
 * Depreciable-asset property tax valuation (STEP L10, 償却資産税の評価額). Pure
 * calculation, no entity access. This is a tax valuation independent of the
 * accounting depreciation (L12).
 *
 * <p>The theoretical book value (評価額) of a depreciable asset decays with the
 * old declining-balance method (旧定率法相当):
 * <ul>
 * <li>Acquisition year: {@code acquisitionCost x (1 - r/2)} — half a year of
 *     depreciation regardless of the month acquired (取得初年度は半年分).</li>
 * <li>Each following year: {@code previous x (1 - r)}.</li>
 * <li>Floor: never below 5% of the acquisition cost (下限は取得価額の 5%).</li>
 * </ul>
 * where {@code r} is the annual depreciation rate of the useful life. When the
 * rate is not given it is derived from the useful life {@code n} with the old
 * declining-balance basis (残存率 10% at the end of the life):
 * {@code r = 1 - 0.1^(1/n)}.
 *
 * <p>{@code yearsElapsed} counts the valuation year relative to the acquisition
 * year: 1 in the acquisition year's following January 1 is the first assessed
 * value (the asset acquired in year Y is first assessed on January 1 of Y+1 with
 * the acquisition-year half-year value). See
 * {@link #assessedValue(BigDecimal, BigDecimal, int, int, RoundingMode)}.
 */
public final class PropertyTaxValuation {

    /** Residual floor as a fraction of the acquisition cost (下限 5%). */
    public static final BigDecimal MIN_RESIDUAL_RATE = new BigDecimal("0.05");

    private static final int RATE_SCALE = 10;

    private PropertyTaxValuation() {
    }

    /**
     * Annual depreciation rate for a useful life using the old declining-balance
     * basis (残存率 10% at the end of the useful life): {@code r = 1 - 0.1^(1/n)}.
     *
     * @param usefulLifeYears useful life in years (&gt; 0)
     * @return the annual depreciation rate (0 &lt; r &lt; 1), scale 10
     * @throws IllegalArgumentException when the useful life is not positive
     */
    public static BigDecimal depreciationRateForLife(int usefulLifeYears) {
        if (usefulLifeYears <= 0) {
            throw new IllegalArgumentException("usefulLifeYears must be greater than zero");
        }
        double r = 1.0 - Math.pow(0.1, 1.0 / usefulLifeYears);
        return new BigDecimal(r).setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Theoretical book value (評価額) at the beginning of a valuation year.
     *
     * @param acquisitionCost  acquisition cost (&gt; 0)
     * @param depreciationRate annual depreciation rate r (0 &lt; r &lt; 1)
     * @param yearsElapsed     number of valuation years applied: 0 = acquisition
     *                         cost (before any depreciation), 1 = after the
     *                         acquisition-year half-year depreciation (the first
     *                         assessed value), 2.. = each following full year
     * @param scale            currency scale
     * @param mode             currency rounding mode
     * @return the valuation, floored at 5% of the acquisition cost
     * @throws IllegalArgumentException when the inputs are invalid
     */
    public static BigDecimal valuation(BigDecimal acquisitionCost, BigDecimal depreciationRate, int yearsElapsed,
            int scale, RoundingMode mode) {
        if (acquisitionCost == null || acquisitionCost.signum() <= 0) {
            throw new IllegalArgumentException("acquisitionCost must be greater than zero");
        }
        if (depreciationRate == null || depreciationRate.signum() <= 0 || depreciationRate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("depreciationRate must be between 0 and 1 (exclusive)");
        }
        if (yearsElapsed < 0) {
            throw new IllegalArgumentException("yearsElapsed must not be negative");
        }
        BigDecimal floor = acquisitionCost.multiply(MIN_RESIDUAL_RATE);
        if (yearsElapsed == 0) {
            return acquisitionCost.setScale(scale, mode);
        }
        // Half-year residual for the acquisition year, then full-year residuals.
        BigDecimal firstYear = BigDecimal.ONE.subtract(depreciationRate.divide(new BigDecimal("2"), RATE_SCALE, mode));
        BigDecimal yearly = BigDecimal.ONE.subtract(depreciationRate);
        BigDecimal value = acquisitionCost.multiply(firstYear);
        for (int y = 2; y <= yearsElapsed; y++) {
            value = value.multiply(yearly);
        }
        if (value.compareTo(floor) < 0) {
            value = floor;
        }
        return value.setScale(scale, mode);
    }

    /**
     * Assessed value (課税標準の基礎となる評価額) of an asset as of January 1 of a
     * tax year, from its acquisition year and the valuation year.
     *
     * <p>An asset acquired in {@code acquisitionYear} is first on the tax rolls on
     * January 1 of {@code acquisitionYear + 1} with the acquisition-year half-year
     * value (yearsElapsed = 1). For a later tax year {@code taxYear} the valuation
     * uses {@code yearsElapsed = taxYear - acquisitionYear}.
     *
     * @return the valuation, or zero when the tax year is not after the acquisition year
     */
    public static BigDecimal assessedValue(BigDecimal acquisitionCost, BigDecimal depreciationRate,
            int acquisitionYear, int taxYear, int scale, RoundingMode mode) {
        int yearsElapsed = taxYear - acquisitionYear;
        if (yearsElapsed <= 0) {
            return BigDecimal.ZERO.setScale(scale, mode);
        }
        return valuation(acquisitionCost, depreciationRate, yearsElapsed, scale, mode);
    }
}
