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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.finance.common.FinanceGlHelper;

/**
 * Revenue recognition strategy of a finance lease (selected by
 * {@code LeaseAgreement.revenueMethodEnumId}, LEASE_REV_METHOD): the start
 * entries (STEP L3), the account credited by a lease payment invoice and the
 * entries of the periodic recognition of the interest equivalent (STEP L4).
 */
public interface LeaseRevenueStrategy {

    String GL_INVESTMENT = "LEASE_INVESTMENT";
    String GL_RECEIVABLE = "LEASE_RECEIVABLE";
    String GL_ASSET_SUSPENSE = "LEASE_ASSET_SUSPENSE";
    String GL_SALES = "LEASE_SALES";
    String GL_COST_OF_SALES = "LEASE_COST_OF_SALES";
    String GL_DEFER_PROVISION = "LEASE_DEFER_PROV";
    String GL_DEFERRED_PROFIT = "LEASE_DEFER_PROFIT";
    String GL_PROFIT_RELEASE = "LEASE_PROFIT_RELEASE";
    String GL_TAX_PAYABLE = "LEASE_TAX_PAYABLE";
    String GL_TAX_RECEIVABLE = "LEASE_TAX_RECEIVABLE";

    /** GL lines booked at the lease start (acceptance date). */
    List<FinanceGlHelper.Line> startEntries(GenericValue agreement);

    /**
     * GL account type credited by the lease payment item of an installment invoice
     * (the invoice debits accounts receivable through the core sales invoice posting).
     */
    String billingCreditAccountType(GenericValue agreement);

    /**
     * GL lines recognizing {@code amount} of the interest equivalent for a period
     * (interest method). A negative amount reverses the lines.
     */
    List<FinanceGlHelper.Line> recognitionEntries(GenericValue agreement, BigDecimal amount);

    /** Strategies by revenue method enumId. */
    Map<String, LeaseRevenueStrategy> REGISTRY = UtilMisc.toMap(
            LeaseProductHelper.REV_AT_START, new AtStart());

    /** The asset account of the lease: receivable (ownership transfer) or investment (no transfer). */
    static String assetAccountType(GenericValue agreement) {
        return LeaseProductHelper.CLASS_FIN_TRANSFER.equals(agreement.getString("leaseClassEnumId"))
                ? GL_RECEIVABLE : GL_INVESTMENT;
    }

    /**
     * Method 1 (第1法): sales and cost of sales at the lease start; the interest
     * equivalent is deferred and released with the interest method
     * ({@link LeaseProfitAllocator}, STEP L4).
     *
     * <pre>
     *   Dr lease investment / receivable   total lease payments   Cr lease sales
     *   Dr lease cost of sales             cost - residual        Cr lease asset (suspense)
     *   Dr lease investment / receivable   residual               Cr lease asset (suspense)
     *   Dr deferred profit provision       interest equivalent    Cr deferred lease profit
     * </pre>
     * The investment asset is the lease payments plus the residual value; sales
     * minus cost of sales equals the deferred lease profit at the start.
     */
    final class AtStart implements LeaseRevenueStrategy {
        @Override
        public List<FinanceGlHelper.Line> startEntries(GenericValue agreement) {
            BigDecimal totalLease = nz(agreement.getBigDecimal("totalLeaseAmount"));
            BigDecimal cost = nz(agreement.getBigDecimal("acquisitionCost"));
            BigDecimal residual = nz(agreement.getBigDecimal("residualValue"));
            BigDecimal interest = nz(agreement.getBigDecimal("totalInterestAmount"));
            String asset = assetAccountType(agreement);
            List<FinanceGlHelper.Line> lines = new ArrayList<>();
            lines.add(new FinanceGlHelper.Line(asset, true, totalLease));
            lines.add(new FinanceGlHelper.Line(GL_SALES, false, totalLease));
            lines.add(new FinanceGlHelper.Line(GL_COST_OF_SALES, true, cost.subtract(residual)));
            lines.add(new FinanceGlHelper.Line(asset, true, residual));
            lines.add(new FinanceGlHelper.Line(GL_ASSET_SUSPENSE, false, cost));
            lines.add(new FinanceGlHelper.Line(GL_DEFER_PROVISION, true, interest));
            lines.add(new FinanceGlHelper.Line(GL_DEFERRED_PROFIT, false, interest));
            return lines;
        }

        /** Billing reduces the lease investment / receivable booked at the start (no sales at billing). */
        @Override
        public String billingCreditAccountType(GenericValue agreement) {
            return assetAccountType(agreement);
        }

        /** Dr deferred lease profit / Cr deferred lease profit release (income). */
        @Override
        public List<FinanceGlHelper.Line> recognitionEntries(GenericValue agreement, BigDecimal amount) {
            boolean forward = amount.signum() >= 0;
            BigDecimal abs = amount.abs();
            List<FinanceGlHelper.Line> lines = new ArrayList<>();
            lines.add(new FinanceGlHelper.Line(GL_DEFERRED_PROFIT, forward, abs));
            lines.add(new FinanceGlHelper.Line(GL_PROFIT_RELEASE, !forward, abs));
            return lines;
        }

        private static BigDecimal nz(BigDecimal v) {
            return v == null ? BigDecimal.ZERO : v;
        }
    }
}
