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
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.util.UtilMisc;

/**
 * Calculation method of a public charge type (公租公課, STEP L10b), selected by
 * {@code LeasePublicChargeType.calcMethodEnumId} (LEASE_PCHG_CALC). Pure
 * calculation, no entity access: the services resolve the rates, jurisdictions
 * and asset attributes and pass them in. Same idea as {@link LeaseRevenueStrategy}:
 * a new charge type with a different rule adds an implementation to
 * {@link #REGISTRY}.
 *
 * <ul>
 * <li>{@link ValueBased} ({@code LSPC_CALC_VALUE}): 償却資産税. Each asset is valued
 *     with {@link PropertyTaxValuation}; the ownership share and the special
 *     taxable-base ratio (課税標準の特例) are applied; the declaration sums the
 *     taxable values of a jurisdiction, applies the 免税点 to the sum, floors the
 *     taxable base and the charge, and multiplies by the rate.</li>
 * <li>{@link UnitAmount} ({@code LSPC_CALC_UNIT}): 自動車税種別割 / 自動車重量税. Each
 *     asset owes the annual amount of its rate class per unit, times the
 *     quantity and the ownership share; the declaration sums them.</li>
 * </ul>
 */
public interface LeasePublicChargeCalculator {

    String CALC_VALUE = "LSPC_CALC_VALUE";
    String CALC_UNIT = "LSPC_CALC_UNIT";

    /** Rules of a charge type in a currency. */
    final class Rules {
        private final BigDecimal exemptionThreshold;
        private final BigDecimal baseRoundingUnit;
        private final BigDecimal taxRoundingUnit;
        private final int scale;
        private final RoundingMode mode;

        public Rules(BigDecimal exemptionThreshold, BigDecimal baseRoundingUnit, BigDecimal taxRoundingUnit,
                int scale, RoundingMode mode) {
            this.exemptionThreshold = exemptionThreshold;
            this.baseRoundingUnit = baseRoundingUnit;
            this.taxRoundingUnit = taxRoundingUnit;
            this.scale = scale;
            this.mode = mode;
        }

        /** 免税点; null = none. */
        public BigDecimal getExemptionThreshold() {
            return exemptionThreshold;
        }

        public int getScale() {
            return scale;
        }

        public RoundingMode getMode() {
            return mode;
        }

        BigDecimal floorBase(BigDecimal v) {
            return floor(v, baseRoundingUnit);
        }

        BigDecimal floorTax(BigDecimal v) {
            return floor(v, taxRoundingUnit);
        }

        private BigDecimal floor(BigDecimal v, BigDecimal unit) {
            if (unit == null || unit.signum() <= 0) {
                return v.setScale(scale, mode);
            }
            return v.divide(unit, 0, RoundingMode.DOWN).multiply(unit).setScale(scale, mode);
        }
    }

    /** One asset to assess. Unused fields of the other method may be null. */
    final class AssetInput {
        private BigDecimal acquisitionCost;
        private int acquisitionYear;
        private BigDecimal depreciationRate;
        private BigDecimal quantity = BigDecimal.ONE;
        private BigDecimal ownershipShare = BigDecimal.ONE;
        private BigDecimal taxableBaseRatio = BigDecimal.ONE;
        private BigDecimal unitAmount;

        public AssetInput acquisitionCost(BigDecimal v) {
            this.acquisitionCost = v;
            return this;
        }

        public AssetInput acquisitionYear(int v) {
            this.acquisitionYear = v;
            return this;
        }

        public AssetInput depreciationRate(BigDecimal v) {
            this.depreciationRate = v;
            return this;
        }

        public AssetInput quantity(BigDecimal v) {
            this.quantity = v == null ? BigDecimal.ONE : v;
            return this;
        }

        public AssetInput ownershipShare(BigDecimal v) {
            this.ownershipShare = v == null ? BigDecimal.ONE : v;
            return this;
        }

        public AssetInput taxableBaseRatio(BigDecimal v) {
            this.taxableBaseRatio = v == null ? BigDecimal.ONE : v;
            return this;
        }

        public AssetInput unitAmount(BigDecimal v) {
            this.unitAmount = v;
            return this;
        }

        void validate() {
            if (ownershipShare.signum() <= 0 || ownershipShare.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("ownershipShare must be greater than 0 and at most 1");
            }
            if (taxableBaseRatio.signum() <= 0 || taxableBaseRatio.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("taxableBaseRatio must be greater than 0 and at most 1");
            }
            if (quantity.signum() <= 0) {
                throw new IllegalArgumentException("quantity must be greater than zero");
            }
        }
    }

    /** Result of one asset. VALUE fills the values, UNIT fills unitAmount / chargeAmount. */
    final class AssetLine {
        private final BigDecimal fullAssessedValue;
        private final BigDecimal assessedValue;
        private final BigDecimal taxableValue;
        private final BigDecimal chargeAmount;

        AssetLine(BigDecimal fullAssessedValue, BigDecimal assessedValue, BigDecimal taxableValue, BigDecimal chargeAmount) {
            this.fullAssessedValue = fullAssessedValue;
            this.assessedValue = assessedValue;
            this.taxableValue = taxableValue;
            this.chargeAmount = chargeAmount;
        }

        public BigDecimal getFullAssessedValue() {
            return fullAssessedValue;
        }

        public BigDecimal getAssessedValue() {
            return assessedValue;
        }

        public BigDecimal getTaxableValue() {
            return taxableValue;
        }

        public BigDecimal getChargeAmount() {
            return chargeAmount;
        }
    }

    /** Totals of one declaration (one jurisdiction). */
    final class Totals {
        private final BigDecimal totalAssessedValue;
        private final BigDecimal taxableBase;
        private final boolean belowThreshold;
        private final BigDecimal taxAmount;

        Totals(BigDecimal totalAssessedValue, BigDecimal taxableBase, boolean belowThreshold, BigDecimal taxAmount) {
            this.totalAssessedValue = totalAssessedValue;
            this.taxableBase = taxableBase;
            this.belowThreshold = belowThreshold;
            this.taxAmount = taxAmount;
        }

        public BigDecimal getTotalAssessedValue() {
            return totalAssessedValue;
        }

        public BigDecimal getTaxableBase() {
            return taxableBase;
        }

        public boolean isBelowThreshold() {
            return belowThreshold;
        }

        public BigDecimal getTaxAmount() {
            return taxAmount;
        }
    }

    /**
     * Assess one asset for a tax year.
     *
     * @return the line, or null when the asset is not assessed in the tax year
     * @throws IllegalArgumentException when the input is invalid
     */
    AssetLine assess(Rules rules, AssetInput asset, int taxYear);

    /** Totals of the lines of one jurisdiction; {@code taxRate} is used by VALUE only. */
    Totals declare(Rules rules, List<AssetLine> lines, BigDecimal taxRate);

    /**
     * Yearly amount of a single asset for an estimate (the 免税点 is not applied:
     * one contract cannot judge the declarant's total).
     */
    BigDecimal estimateYear(Rules rules, AssetInput asset, int taxYear, BigDecimal taxRate);

    /** Calculators by LEASE_PCHG_CALC enumId. */
    Map<String, LeasePublicChargeCalculator> REGISTRY = UtilMisc.toMap(
            CALC_VALUE, new ValueBased(),
            CALC_UNIT, new UnitAmount());

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** 償却資産税: assessed value (旧定率法相当) x share x special ratio, aggregated with the 免税点. */
    final class ValueBased implements LeasePublicChargeCalculator {
        @Override
        public AssetLine assess(Rules rules, AssetInput a, int taxYear) {
            a.validate();
            BigDecimal full = PropertyTaxValuation.assessedValue(a.acquisitionCost, a.depreciationRate,
                    a.acquisitionYear, taxYear, rules.scale, rules.mode);
            if (full.signum() <= 0) {
                return null;
            }
            BigDecimal assessed = full.multiply(a.ownershipShare).setScale(rules.scale, rules.mode);
            BigDecimal taxable = assessed.multiply(a.taxableBaseRatio).setScale(rules.scale, rules.mode);
            return new AssetLine(full, assessed, taxable, null);
        }

        @Override
        public Totals declare(Rules rules, List<AssetLine> lines, BigDecimal taxRate) {
            BigDecimal assessed = BigDecimal.ZERO;
            BigDecimal taxable = BigDecimal.ZERO;
            for (AssetLine l : lines) {
                assessed = assessed.add(nz(l.assessedValue));
                taxable = taxable.add(nz(l.taxableValue));
            }
            // The 免税点 is judged on the taxable base (課税標準となるべき額) of the declarant in the jurisdiction.
            boolean below = rules.exemptionThreshold != null && taxable.compareTo(rules.exemptionThreshold) < 0;
            BigDecimal zero = BigDecimal.ZERO.setScale(rules.scale, rules.mode);
            BigDecimal base = below ? zero : rules.floorBase(taxable);
            BigDecimal tax = below ? zero : rules.floorTax(base.multiply(nz(taxRate)));
            return new Totals(assessed.setScale(rules.scale, rules.mode), base, below, tax);
        }

        @Override
        public BigDecimal estimateYear(Rules rules, AssetInput a, int taxYear, BigDecimal taxRate) {
            AssetLine line = assess(rules, a, taxYear);
            if (line == null) {
                return BigDecimal.ZERO.setScale(rules.scale, rules.mode);
            }
            return rules.floorBase(line.taxableValue).multiply(nz(taxRate)).setScale(rules.scale, rules.mode);
        }
    }

    /** 自動車税種別割 / 自動車重量税: annual amount of the rate class x quantity x share. */
    final class UnitAmount implements LeasePublicChargeCalculator {
        @Override
        public AssetLine assess(Rules rules, AssetInput a, int taxYear) {
            a.validate();
            if (a.unitAmount == null || a.unitAmount.signum() < 0) {
                throw new IllegalArgumentException("unitAmount is required");
            }
            BigDecimal charge = a.unitAmount.multiply(a.quantity).multiply(a.ownershipShare).setScale(rules.scale, rules.mode);
            return new AssetLine(null, null, null, charge);
        }

        @Override
        public Totals declare(Rules rules, List<AssetLine> lines, BigDecimal taxRate) {
            BigDecimal total = BigDecimal.ZERO;
            for (AssetLine l : lines) {
                total = total.add(nz(l.chargeAmount));
            }
            return new Totals(null, null, false, rules.floorTax(total));
        }

        @Override
        public BigDecimal estimateYear(Rules rules, AssetInput a, int taxYear, BigDecimal taxRate) {
            return assess(rules, a, taxYear).chargeAmount;
        }
    }
}
