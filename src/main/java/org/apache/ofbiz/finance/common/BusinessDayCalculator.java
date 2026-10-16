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
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Utility for business-day determination and due-date adjustment.
 *
 * <p>STEP L15b: the business days come from a {@link FinanceBusinessCalendar} (core
 * {@code TechDataCalendar}: week definition, holidays and working-day exceptions; several
 * calendars combined). The overloads without a calendar use
 * {@link FinanceBusinessCalendar#WEEKEND_ONLY} (Saturday and Sunday only), the behaviour
 * before STEP L15b.
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

    /** Guard against a calendar that closes every day. */
    private static final int MAX_SHIFT_DAYS = 366;

    private BusinessDayCalculator() {
    }

    // -----------------------------------------------------------------------
    // Business-day test
    // -----------------------------------------------------------------------

    /** Returns {@code true} if {@code date} is a business day (Mon–Fri, no holidays). */
    public static boolean isBusinessDay(LocalDate date) {
        return isBusinessDay(date, FinanceBusinessCalendar.WEEKEND_ONLY);
    }

    /** Returns {@code true} if {@code date} is a business day of {@code calendar}. */
    public static boolean isBusinessDay(LocalDate date, FinanceBusinessCalendar calendar) {
        return (calendar == null ? FinanceBusinessCalendar.WEEKEND_ONLY : calendar).isBusinessDay(date);
    }

    // -----------------------------------------------------------------------
    // Due-date adjustment
    // -----------------------------------------------------------------------

    /** Adjust {@code date} with Saturday/Sunday as the only non-business days. */
    public static LocalDate adjust(LocalDate date, String conventionId) {
        return adjust(date, conventionId, FinanceBusinessCalendar.WEEKEND_ONLY);
    }

    /**
     * Adjust {@code date} to a business day of {@code calendar} according to the given convention.
     *
     * @param date         the calendar due date to adjust
     * @param conventionId enumId from LOAN_DUEDATE_CONV
     * @param calendar     business-day calendar (null = Saturday/Sunday only)
     * @return adjusted business day (or the original date if no adjustment needed)
     */
    public static LocalDate adjust(LocalDate date, String conventionId, FinanceBusinessCalendar calendar) {
        if (conventionId == null || "DUEDATE_NONE".equals(conventionId)) {
            return date;
        }
        switch (conventionId) {
            case "DUEDATE_FOLLOWING":
                return following(date, calendar);
            case "DUEDATE_PRECEDING":
                return preceding(date, calendar);
            case "DUEDATE_MOD_FOLLOW":
                return modifiedFollowing(date, calendar);
            default:
                return date;
        }
    }

    /** Adjust a {@link Timestamp} with Saturday/Sunday as the only non-business days. */
    public static Timestamp adjust(Timestamp ts, String conventionId) {
        return adjust(ts, conventionId, FinanceBusinessCalendar.WEEKEND_ONLY);
    }

    /**
     * Adjust a {@link Timestamp} to a business day of {@code calendar}.
     * The time-of-day component is preserved.
     *
     * @param ts           the original timestamp
     * @param conventionId enumId from LOAN_DUEDATE_CONV
     * @param calendar     business-day calendar (null = Saturday/Sunday only)
     * @return adjusted timestamp
     */
    public static Timestamp adjust(Timestamp ts, String conventionId, FinanceBusinessCalendar calendar) {
        if (ts == null) return null;
        if (conventionId == null || "DUEDATE_NONE".equals(conventionId)) return ts;
        LocalDate original = ts.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate adjusted = adjust(original, conventionId, calendar);
        if (adjusted.equals(original)) return ts;
        // Rebuild timestamp: keep the time-of-day (DST-safe through LocalDateTime).
        return Timestamp.valueOf(ts.toLocalDateTime().plusDays(adjusted.toEpochDay() - original.toEpochDay()));
    }

    // -----------------------------------------------------------------------
    // Convention implementations
    // -----------------------------------------------------------------------

    /** Next business day (stays if already a business day). */
    public static LocalDate following(LocalDate date) {
        return following(date, FinanceBusinessCalendar.WEEKEND_ONLY);
    }

    /** Next business day of {@code calendar} (stays if already a business day). */
    public static LocalDate following(LocalDate date, FinanceBusinessCalendar calendar) {
        return shift(date, 1, calendar);
    }

    /** Previous business day (stays if already a business day). */
    public static LocalDate preceding(LocalDate date) {
        return preceding(date, FinanceBusinessCalendar.WEEKEND_ONLY);
    }

    /** Previous business day of {@code calendar} (stays if already a business day). */
    public static LocalDate preceding(LocalDate date, FinanceBusinessCalendar calendar) {
        return shift(date, -1, calendar);
    }

    /** Modified Following with Saturday/Sunday as the only non-business days. */
    public static LocalDate modifiedFollowing(LocalDate date) {
        return modifiedFollowing(date, FinanceBusinessCalendar.WEEKEND_ONLY);
    }

    /**
     * Modified Following: next business day, unless it crosses into the following
     * calendar month, in which case the preceding business day is used.
     */
    public static LocalDate modifiedFollowing(LocalDate date, FinanceBusinessCalendar calendar) {
        if (isBusinessDay(date, calendar)) return date;
        int originalMonth = date.getMonthValue();
        LocalDate candidate = following(date, calendar);
        if (candidate.getMonthValue() != originalMonth) {
            // Crossed month boundary – use preceding instead.
            return preceding(date, calendar);
        }
        return candidate;
    }

    private static LocalDate shift(LocalDate date, int step, FinanceBusinessCalendar calendar) {
        LocalDate d = date;
        for (int i = 0; i <= MAX_SHIFT_DAYS; i++) {
            if (isBusinessDay(d, calendar)) {
                return d;
            }
            d = d.plusDays(step);
        }
        throw new IllegalStateException("No business day within " + MAX_SHIFT_DAYS + " days of " + date
                + " (calendars " + (calendar == null ? "[]" : calendar.getCalendarIds()) + ")");
    }
}