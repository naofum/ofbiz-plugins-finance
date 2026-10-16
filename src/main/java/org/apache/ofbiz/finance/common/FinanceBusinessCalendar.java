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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.entity.util.EntityUtilProperties;

/**
 * STEP L15b: business-day calendar built from one or more core {@code TechDataCalendar} rows
 * ({@code SPEC_COMMON.md} 12).
 *
 * <ul>
 *   <li>Week definition ({@code TechDataCalendarWeek}): a weekday whose capacity is empty or 0 is closed.
 *       A calendar without a week definition closes Saturday and Sunday.</li>
 *   <li>Exception day ({@code TechDataCalendarExcDay}): capacity empty or 0 = holiday,
 *       capacity &gt; 0 = working day (overrides the week definition).</li>
 *   <li>Several calendars: a day is a business day only when every calendar is open.</li>
 * </ul>
 *
 * <p>Resolution for a product: {@code FinancialProductCalendar} rows → {@code finance.properties}
 * {@code business.calendar.ids} → {@link #WEEKEND_ONLY}. The calendar is read once per call; the entity
 * cache is used, so changes in the core screens are seen after the cache is cleared by the entity engine.
 */
public final class FinanceBusinessCalendar {

    private static final String MODULE = FinanceBusinessCalendar.class.getName();
    private static final String FINANCE_RESOURCE = "finance";

    /** Saturday and Sunday closed, no holidays (the behaviour before STEP L15b). */
    public static final FinanceBusinessCalendar WEEKEND_ONLY =
            new FinanceBusinessCalendar(Collections.singletonList(Definition.weekendOnly("")));

    private static final String[] DAY_FIELDS = {"monday", "tuesday", "wednesday", "thursday", "friday",
        "saturday", "sunday"};

    private final List<Definition> definitions;

    private FinanceBusinessCalendar(List<Definition> definitions) {
        this.definitions = definitions;
    }

    /** One calendar: closed weekdays and exception days (true = open, false = closed). */
    private static final class Definition {
        private final String calendarId;
        private final boolean[] closedWeekday = new boolean[7];
        private final Map<LocalDate, Boolean> exceptions = new HashMap<>();

        private Definition(String calendarId) {
            this.calendarId = calendarId;
        }

        private static Definition weekendOnly(String calendarId) {
            Definition d = new Definition(calendarId);
            d.closedWeekday[DayOfWeek.SATURDAY.getValue() - 1] = true;
            d.closedWeekday[DayOfWeek.SUNDAY.getValue() - 1] = true;
            return d;
        }

        private boolean isOpen(LocalDate date) {
            Boolean exception = exceptions.get(date);
            if (exception != null) {
                return exception;
            }
            return !closedWeekday[date.getDayOfWeek().getValue() - 1];
        }
    }

    /** Ids of the calendars used (empty for {@link #WEEKEND_ONLY}). */
    public List<String> getCalendarIds() {
        List<String> ids = new ArrayList<>();
        for (Definition d : definitions) {
            if (UtilValidate.isNotEmpty(d.calendarId)) {
                ids.add(d.calendarId);
            }
        }
        return ids;
    }

    /** {@code true} when every calendar is open on {@code date}. */
    public boolean isBusinessDay(LocalDate date) {
        for (Definition d : definitions) {
            if (!d.isOpen(date)) {
                return false;
            }
        }
        return true;
    }

    // -----------------------------------------------------------------------
    // Loading
    // -----------------------------------------------------------------------

    /** Calendar of a financial product (product rows → default → weekend only). */
    public static FinanceBusinessCalendar forProduct(Delegator delegator, String productId) {
        List<String> ids = new ArrayList<>();
        if (UtilValidate.isNotEmpty(productId)) {
            try {
                for (GenericValue row : EntityQuery.use(delegator).from("FinancialProductCalendar")
                        .where("productId", productId).orderBy("calendarId").cache().queryList()) {
                    ids.add(row.getString("calendarId"));
                }
            } catch (GenericEntityException e) {
                Debug.logError(e, "Cannot read the calendars of product " + productId, MODULE);
            }
        }
        if (ids.isEmpty()) {
            ids.addAll(defaultCalendarIds(delegator));
        }
        return load(delegator, ids);
    }

    /** Default calendar ids from {@code business.calendar.ids} (SystemProperty or finance.properties). */
    public static List<String> defaultCalendarIds(Delegator delegator) {
        String value = EntityUtilProperties.getPropertyValue(FINANCE_RESOURCE, "business.calendar.ids", "", delegator);
        List<String> ids = new ArrayList<>();
        if (UtilValidate.isNotEmpty(value)) {
            for (String id : value.split(",")) {
                if (UtilValidate.isNotEmpty(id.trim())) {
                    ids.add(id.trim());
                }
            }
        }
        return ids;
    }

    /**
     * Calendar from the given TechDataCalendar ids. Unknown ids are skipped (logged);
     * no usable id gives {@link #WEEKEND_ONLY}.
     */
    public static FinanceBusinessCalendar load(Delegator delegator, Collection<String> calendarIds) {
        if (calendarIds == null || calendarIds.isEmpty()) {
            return WEEKEND_ONLY;
        }
        Set<String> unique = new LinkedHashSet<>(calendarIds);
        List<Definition> definitions = new ArrayList<>();
        for (String calendarId : unique) {
            try {
                Definition d = loadOne(delegator, calendarId);
                if (d != null) {
                    definitions.add(d);
                }
            } catch (GenericEntityException e) {
                Debug.logError(e, "Cannot read the business-day calendar " + calendarId, MODULE);
            }
        }
        return definitions.isEmpty() ? WEEKEND_ONLY : new FinanceBusinessCalendar(definitions);
    }

    private static Definition loadOne(Delegator delegator, String calendarId) throws GenericEntityException {
        GenericValue calendar = EntityQuery.use(delegator).from("TechDataCalendar")
                .where("calendarId", calendarId).cache().queryOne();
        if (calendar == null) {
            Debug.logWarning("Business-day calendar " + calendarId + " does not exist; it is ignored", MODULE);
            return null;
        }
        Definition d;
        GenericValue week = UtilValidate.isEmpty(calendar.getString("calendarWeekId")) ? null
                : EntityQuery.use(delegator).from("TechDataCalendarWeek")
                        .where("calendarWeekId", calendar.getString("calendarWeekId")).cache().queryOne();
        if (week == null) {
            d = Definition.weekendOnly(calendarId);
        } else {
            d = new Definition(calendarId);
            for (int i = 0; i < DAY_FIELDS.length; i++) {
                d.closedWeekday[i] = !hasCapacity(week.get(DAY_FIELDS[i] + "Capacity"));
            }
        }
        for (GenericValue exception : EntityQuery.use(delegator).from("TechDataCalendarExcDay")
                .where("calendarId", calendarId).cache().queryList()) {
            Timestamp start = exception.getTimestamp("exceptionDateStartTime");
            if (start == null) {
                continue;
            }
            LocalDate date = start.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            d.exceptions.put(date, hasCapacity(exception.get("exceptionCapacity")));
        }
        return d;
    }

    private static boolean hasCapacity(Object capacity) {
        return capacity instanceof Number && ((Number) capacity).doubleValue() > 0;
    }
}
