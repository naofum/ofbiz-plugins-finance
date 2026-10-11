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

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Utility for business-day determination and due-date adjustment.
 *
 * <p>Currently treats Saturday and Sunday as non-business days.
 * Public holidays are not considered in this initial implementation;
 * a future extension can add a configurable holiday calendar
 * (e.g. via OFBiz TemporalExpression or a SystemProperty-referenced list).
 *
 * <p>Supported due-date adjustment conventions (enumId from LOAN_DUEDATE_CONV):
 * <ul>
 *   <li>{@code DUEDATE_NONE}       – No adjustment (returns the date unchanged)</li>
 *   <li>{@code DUEDATE_FOLLOWING}  – Next business day</li>
 *   <li>{@code DUEDATE_PRECEDING}  – Previous business day</li>
 *   <li>{@code DUEDATE_MOD_FOLLOW} – Modified Following: next business day unless it
 *       falls in the following month, in which case the preceding business day is used</li>
 * </ul>
 */
public final class BusinessDayCalculator {

    private BusinessDayCalculator() {
    }

    // -----------------------------------------------------------------------
    // Business-day test
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if {@code date} is a business day (Mon–Fri).
     */
    public static boolean isBusinessDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }

    // -----------------------------------------------------------------------
    // Due-date adjustment
    // -----------------------------------------------------------------------

    /**
     * Adjust {@code date} to a business day according to the given convention.
     *
     * @param date         the calendar due date to adjust
     * @param conventionId enumId from LOAN_DUEDATE_CONV
     * @return adjusted business day (or the original date if no adjustment needed)
     */
    public static LocalDate adjust(LocalDate date, String conventionId) {
        if (conventionId == null || "DUEDATE_NONE".equals(conventionId)) {
            return date;
        }
        switch (conventionId) {
            case "DUEDATE_FOLLOWING":
                return following(date);
            case "DUEDATE_PRECEDING":
                return preceding(date);
            case "DUEDATE_MOD_FOLLOW":
                return modifiedFollowing(date);
            default:
                return date;
        }
    }

    /**
     * Adjust a {@link Timestamp} to a business day.
     * The time-of-day component is preserved.
     *
     * @param ts           the original timestamp
     * @param conventionId enumId from LOAN_DUEDATE_CONV
     * @return adjusted timestamp
     */
    public static Timestamp adjust(Timestamp ts, String conventionId) {
        if (ts == null) return null;
        if (conventionId == null || "DUEDATE_NONE".equals(conventionId)) return ts;
        LocalDate original = ts.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate adjusted = adjust(original, conventionId);
        if (adjusted.equals(original)) return ts;
        // Rebuild timestamp: keep time-of-day by shifting the epoch millis by day difference.
        long deltaDays = adjusted.toEpochDay() - original.toEpochDay();
        return new Timestamp(ts.getTime() + deltaDays * 86_400_000L);
    }

    // -----------------------------------------------------------------------
    // Convention implementations
    // -----------------------------------------------------------------------

    /** Next business day (stays if already a business day). */
    public static LocalDate following(LocalDate date) {
        while (!isBusinessDay(date)) {
            date = date.plusDays(1);
        }
        return date;
    }

    /** Previous business day (stays if already a business day). */
    public static LocalDate preceding(LocalDate date) {
        while (!isBusinessDay(date)) {
            date = date.minusDays(1);
        }
        return date;
    }

    /**
     * Modified Following: next business day, unless it crosses into the following
     * calendar month, in which case the preceding business day is used.
     */
    public static LocalDate modifiedFollowing(LocalDate date) {
        if (isBusinessDay(date)) return date;
        int originalMonth = date.getMonthValue();
        LocalDate candidate = following(date);
        if (candidate.getMonthValue() != originalMonth) {
            // Crossed month boundary – use preceding instead.
            return preceding(date);
        }
        return candidate;
    }
}
