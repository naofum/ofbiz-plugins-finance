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

// Screen-only pre-processing event for underwriting create/update.
//
// The UI takes approvedAnnualInterestRate as a percent (e.g. 15 or 15.5);
// this event divides it by 100 and calls the unchanged create/update service
// with the decimal fraction (0.150000). Create vs. update is chosen by the
// presence of loanUnderwritingId.

import java.math.BigDecimal
import java.math.RoundingMode
import org.apache.ofbiz.base.util.UtilProperties
import org.apache.ofbiz.base.util.UtilValidate
import org.apache.ofbiz.service.ModelService
import org.apache.ofbiz.service.ServiceUtil

boolean isUpdate = UtilValidate.isNotEmpty(parameters.loanUnderwritingId)
String serviceName = isUpdate ? 'updateLoanUnderwriting' : 'createLoanUnderwriting'

// Coerce raw request parameters to the service's declared IN types, then
// override only the approved interest rate (percent -> decimal fraction).
Map serviceCtx = dispatcher.getDispatchContext()
        .makeValidContext(serviceName, ModelService.IN_PARAM, parameters)

// Re-attach the logged-in user explicitly (services are auth="true").
serviceCtx.userLogin = request.getSession().getAttribute('userLogin')
serviceCtx.locale = locale

String rawRate = parameters.approvedAnnualInterestRate
if (UtilValidate.isNotEmpty(rawRate)) {
    try {
        BigDecimal percent = new BigDecimal(rawRate.toString().trim())
        serviceCtx.approvedAnnualInterestRate = percent.divide(new BigDecimal('100'), 6, RoundingMode.HALF_UP)
    } catch (NumberFormatException e) {
        request.setAttribute('_ERROR_MESSAGE_',
                UtilProperties.getMessage('FinanceUiLabels', 'FinanceInterestRateInvalid', locale))
        return 'error'
    }
} else {
    serviceCtx.remove('approvedAnnualInterestRate')
}

try {
    Map result = dispatcher.runSync(serviceName, serviceCtx)
    if (ServiceUtil.isError(result)) {
        request.setAttribute('_ERROR_MESSAGE_', ServiceUtil.getErrorMessage(result))
        return 'error'
    }
} catch (Exception e) {
    request.setAttribute('_ERROR_MESSAGE_', e.getMessage())
    return 'error'
}

return 'success'
