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

/**
 * Delinquency state of one contract at a point in time, computed by the
 * contract-type adapter. "Interest" is the interest portion (loans; the interest
 * equivalent for leases) and "tax" the unpaid consumption tax billed with the
 * installments (leases, STEP L6; zero for loans). The late fee base is the overdue
 * amount the late fee is computed on (default: the overdue total).
 */
public final class DelinquencyComputation {

    private final long daysPastDue;
    private final BigDecimal overduePrincipal;
    private final BigDecimal overdueInterest;
    private final BigDecimal overdueTax;
    private final BigDecimal lateFeeBase;

    public DelinquencyComputation(long daysPastDue, BigDecimal overduePrincipal, BigDecimal overdueInterest) {
        this(daysPastDue, overduePrincipal, overdueInterest, BigDecimal.ZERO, null);
    }

    /**
     * @param lateFeeBase overdue amount the late fee is computed on; null = the overdue total
     */
    public DelinquencyComputation(long daysPastDue, BigDecimal overduePrincipal, BigDecimal overdueInterest,
            BigDecimal overdueTax, BigDecimal lateFeeBase) {
        this.daysPastDue = daysPastDue;
        this.overduePrincipal = overduePrincipal == null ? BigDecimal.ZERO : overduePrincipal;
        this.overdueInterest = overdueInterest == null ? BigDecimal.ZERO : overdueInterest;
        this.overdueTax = overdueTax == null ? BigDecimal.ZERO : overdueTax;
        this.lateFeeBase = lateFeeBase == null ? getOverdueTotal() : lateFeeBase;
    }

    public long getDaysPastDue() {
        return daysPastDue;
    }

    public BigDecimal getOverduePrincipal() {
        return overduePrincipal;
    }

    public BigDecimal getOverdueInterest() {
        return overdueInterest;
    }

    public BigDecimal getOverdueTax() {
        return overdueTax;
    }

    /** Principal + interest + tax. */
    public BigDecimal getOverdueTotal() {
        return overduePrincipal.add(overdueInterest).add(overdueTax);
    }

    public BigDecimal getLateFeeBase() {
        return lateFeeBase;
    }
}
