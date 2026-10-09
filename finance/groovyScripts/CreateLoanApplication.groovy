/*
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
 */

import org.apache.ofbiz.base.util.UtilDateTime
import org.apache.ofbiz.entity.GenericValue

// Create applications only in the initial RECEIVED state. All later status
// changes must go through setLoanApplicationStatus.
String loanApplicationId = delegator.getNextSeqId('LoanApplication')
GenericValue application = makeValue('LoanApplication', [
        loanApplicationId        : loanApplicationId,
        productId                : context.productId,
        applicantPartyId         : context.applicantPartyId,
        statusId                 : 'LOANAPP_RECEIVED',
        currencyUomId            : context.currencyUomId,
        requestedPrincipalAmount : context.requestedPrincipalAmount,
        requestedTermMonths      : context.requestedTermMonths,
        applicationDate          : context.applicationDate ?: UtilDateTime.nowTimestamp(),
        purpose                  : context.purpose,
        comments                 : context.comments])
application.create()

Map result = success()
result.loanApplicationId = loanApplicationId
return result
