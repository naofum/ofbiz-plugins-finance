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

// Screen-only pre-processing event for quote creation.
//
// The UI takes the annual interest rate as a percent (e.g. 15 or 15.5),
// but the database/model and all calculations use the decimal fraction
// (0.150000). This event divides the entered percent by 100 and then calls
// the unchanged createLoanQuote service with the decimal value, so the
// service, calculations, tests and seed data stay in decimal form.

import java.math.BigDecimal
import java.math.RoundingMode
import org.apache.ofbiz.base.util.UtilProperties
import org.apache.ofbiz.base.util.UtilValidate
import org.apache.ofbiz.service.ModelService
import org.apache.ofbiz.service.ServiceUtil

// Let the service engine coerce the raw request parameters to the service's
// declared IN types (BigDecimal/Long/Timestamp), exactly as a direct
// <event type="service"> would, then override only the interest rate.
Map serviceCtx = dispatcher.getDispatchContext()
        .makeValidContext('createLoanQuote', ModelService.IN_PARAM, parameters)

// makeValidContext only keeps declared IN attributes; the service is auth="true"
// so re-attach the logged-in user explicitly.
serviceCtx.userLogin = request.getSession().getAttribute('userLogin')
serviceCtx.locale = locale

// Percent -> decimal fraction (e.g. "15.5" -> 0.155000). Left empty to let the
// service fall back to the financial product's default rate.
String rawRate = parameters.annualInterestRate
if (UtilValidate.isNotEmpty(rawRate)) {
    try {
        BigDecimal percent = new BigDecimal(rawRate.toString().trim())
        serviceCtx.annualInterestRate = percent.divide(new BigDecimal('100'), 6, RoundingMode.HALF_UP)
    } catch (NumberFormatException e) {
        request.setAttribute('_ERROR_MESSAGE_',
                UtilProperties.getMessage('FinanceUiLabels', 'FinanceInterestRateInvalid', locale))
        return 'error'
    }
} else {
    serviceCtx.remove('annualInterestRate')
}

try {
    Map result = dispatcher.runSync('createLoanQuote', serviceCtx)
    if (ServiceUtil.isError(result)) {
        request.setAttribute('_ERROR_MESSAGE_', ServiceUtil.getErrorMessage(result))
        return 'error'
    }
    if (result.loanQuoteId != null) {
        request.setAttribute('loanQuoteId', result.loanQuoteId)
    }
} catch (Exception e) {
    request.setAttribute('_ERROR_MESSAGE_', e.getMessage())
    return 'error'
}

return 'success'
