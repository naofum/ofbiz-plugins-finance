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
import java.util.List;
import java.util.Map;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;

/**
 * Bridge between the common layer and one contract type (loan / lease).
 *
 * <p>The common delinquency engine works on {@code FinanceContract} and asks the
 * adapter of the contract's type for everything that depends on the layer's own
 * entities (agreement, confirmed schedule, installment invoices). Implementations
 * live in the loan / lease layers and are registered in {@code finance.properties}
 * ({@code contract.adapter.<contractTypeId>=<class>}), so the common layer never
 * references the loan or lease layer at compile time.
 */
public interface FinanceContractAdapter {

    /** Contract type handled by this adapter, e.g. {@code LOAN}. */
    String getContractTypeId();

    /** Field name of the layer agreement id, e.g. {@code loanAgreementId}. */
    String getAgreementIdField();

    /** Entity name of the layer agreement, e.g. {@code LoanAgreement}. */
    String getAgreementEntityName();

    /** Short label used in GL transaction descriptions, e.g. {@code Loan}. */
    String getDescriptionPrefix();

    /** Resolve the financeContractId from a layer agreement id, or null when unknown. */
    String findContractIdByAgreementId(Delegator delegator, String agreementId) throws GenericEntityException;

    /** The layer agreement of a contract (fresh read), or null. */
    GenericValue findAgreement(Delegator delegator, String financeContractId) throws GenericEntityException;

    /** Whether the agreement is in the layer's active (billing) status. */
    boolean isActive(GenericValue agreement);

    /** Accounting organization (lessor / lender) of the agreement. */
    String getOrganizationPartyId(Delegator delegator, GenericValue agreement) throws GenericEntityException;

    /** Past-due amounts and days past due of the agreement as of the given date. */
    DelinquencyComputation computeDelinquency(Delegator delegator, GenericValue agreement, Timestamp asOfDate)
            throws GenericEntityException;

    /** Invoices of the agreement that are neither paid nor already written off. */
    List<String> findOpenInvoiceIds(Delegator delegator, GenericValue agreement) throws GenericEntityException;

    /**
     * Layer key fields to store on common delinquency records for backward
     * compatibility (e.g. {@code loanAgreementId}); may be empty.
     */
    Map<String, Object> getAgreementKeyFields(GenericValue agreement);
}
