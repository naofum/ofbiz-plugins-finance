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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Allocation of the interest equivalent of a lease to calendar days (STEP L4).
 * Pure calculation, no entity access.
 *
 * <p>Each installment's interest portion (interest method, {@link LeaseCalculator})
 * is earned over one lease period:
 * <ul>
 * <li>payments in arrears: installment k's interest is earned in lease period k;</li>
 * <li>payments in advance: installment k's interest (k &ge; 2) is earned in lease
 *     period k&minus;1, the period that ends when it falls due (installment 1 has no
 *     interest). The interest is therefore fully earned at the end of period n&minus;1.</li>
 * </ul>
 * The cumulative interest earned through a day is the sum of the periods ended by
 * that day plus the current period prorated by days (currency rounding). The
 * cumulative through the last earning day equals the sum of the interest portions
 * exactly, so incremental recognition never double counts and the total released
 * equals the deferred lease profit at the start.
 */
public final class LeaseProfitAllocator {

    /** Interest earned evenly over [fromDate, thruDate] (both inclusive). */
    public static final class Segment {
        private final LocalDate fromDate;
        private final LocalDate thruDate;
        private final BigDecimal amount;

        public Segment(LocalDate fromDate, LocalDate thruDate, BigDecimal amount) {
            if (thruDate.isBefore(fromDate)) {
                throw new IllegalArgumentException("thruDate before fromDate");
            }
            this.fromDate = fromDate;
            this.thruDate = thruDate;
            this.amount = amount == null ? BigDecimal.ZERO : amount;
        }

        public LocalDate getFromDate() {
            return fromDate;
        }

        public LocalDate getThruDate() {
            return thruDate;
        }

        public BigDecimal getAmount() {
            return amount;
        }
    }

    /** One installment of the confirmed schedule (period and interest portion). */
    public static final class Installment {
        private final int number;
        private final LocalDate periodFrom;
        private final LocalDate periodThru;
        private final BigDecimal interest;

        public Installment(int number, LocalDate periodFrom, LocalDate periodThru, BigDecimal interest) {
            this.number = number;
            this.periodFrom = periodFrom;
            this.periodThru = periodThru;
            this.interest = interest == null ? BigDecimal.ZERO : interest;
        }
    }

    private final List<Segment> segments;
    private final int scale;
    private final RoundingMode roundingMode;

    public LeaseProfitAllocator(List<Segment> segments, int scale, RoundingMode roundingMode) {
        List<Segment> sorted = new ArrayList<>(segments);
        sorted.sort((a, b) -> a.fromDate.compareTo(b.fromDate));
        this.segments = Collections.unmodifiableList(sorted);
        this.scale = scale;
        this.roundingMode = roundingMode == null ? RoundingMode.HALF_UP : roundingMode;
    }

    /** Earning segments of a schedule (see the class comment for the timing rule). */
    public static List<Segment> segments(List<Installment> installments, boolean advance) {
        List<Installment> sorted = new ArrayList<>(installments);
        sorted.sort((a, b) -> Integer.compare(a.number, b.number));
        List<Segment> result = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            Installment row = sorted.get(i);
            if (advance) {
                if (i == 0 && row.number > 1) {
                    // STEP L15d: the schedule of a split agreement starts at installment k > 1; its
                    // interest is earned in period k-1 (the month before its own period).
                    if (row.interest.signum() != 0) {
                        result.add(new Segment(row.periodFrom.minusMonths(1), row.periodFrom.minusDays(1), row.interest));
                    }
                    continue;
                }
                if (i == 0) {
                    // Paid at the start; no interest has been earned yet. Any amount is
                    // earned in the first period so the total is still released.
                    if (row.interest.signum() != 0) {
                        result.add(new Segment(row.periodFrom, row.periodThru, row.interest));
                    }
                    continue;
                }
                Installment previous = sorted.get(i - 1);
                result.add(new Segment(previous.periodFrom, previous.periodThru, row.interest));
            } else {
                result.add(new Segment(row.periodFrom, row.periodThru, row.interest));
            }
        }
        return result;
    }

    public List<Segment> getSegments() {
        return segments;
    }

    /** Total interest of all segments. */
    public BigDecimal total() {
        BigDecimal total = BigDecimal.ZERO;
        for (Segment s : segments) {
            total = total.add(s.amount);
        }
        return total;
    }

    /** Last day on which interest is earned, or null when there is no segment. */
    public LocalDate lastEarningDay() {
        LocalDate last = null;
        for (Segment s : segments) {
            if (last == null || s.thruDate.isAfter(last)) {
                last = s.thruDate;
            }
        }
        return last;
    }

    /** First day on which interest is earned, or null when there is no segment. */
    public LocalDate firstEarningDay() {
        return segments.isEmpty() ? null : segments.get(0).fromDate;
    }

    /** Cumulative interest earned from the start through {@code day} (inclusive). */
    public BigDecimal cumulativeThrough(LocalDate day) {
        BigDecimal cumulative = BigDecimal.ZERO;
        for (Segment s : segments) {
            if (!s.thruDate.isAfter(day)) {
                cumulative = cumulative.add(s.amount);
            } else if (!s.fromDate.isAfter(day)) {
                long length = ChronoUnit.DAYS.between(s.fromDate, s.thruDate) + 1;
                long elapsed = ChronoUnit.DAYS.between(s.fromDate, day) + 1;
                cumulative = cumulative.add(s.amount.multiply(BigDecimal.valueOf(elapsed))
                        .divide(BigDecimal.valueOf(length), scale, roundingMode));
            }
        }
        return cumulative.setScale(scale, roundingMode);
    }
}
