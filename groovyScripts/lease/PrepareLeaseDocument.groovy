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

// Controller event (STEP L8): acceptance certificate (documentType=ACCEPTANCE) or
// expiry notice (documentType=EXPIRY) of a lease agreement, rendered by
// LeaseDocument.jrxml. The first print of the acceptance certificate records
// LeaseAcceptance.receiptPrintedDate.

import org.apache.ofbiz.base.util.UtilProperties
import org.apache.ofbiz.finance.lease.LeaseInquiryHelper
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource

String documentType = parameters.documentType ?: LeaseInquiryHelper.DOC_ACCEPTANCE
if (documentType == LeaseInquiryHelper.DOC_ACCEPTANCE) {
    LeaseInquiryHelper.markAcceptanceCertificateIssued(delegator, parameters.leaseAgreementId)
}
Map data = LeaseInquiryHelper.documentData(delegator, documentType, parameters.leaseAgreementId, locale)
if (data == null) {
    request.setAttribute('_ERROR_MESSAGE_', UtilProperties.getMessage('FinanceUiLabels', 'FinanceLeaseDocNotAvailable',
            [leaseAgreementId: parameters.leaseAgreementId ?: ''], locale))
    return 'error'
}
request.setAttribute('jrParameters', data.parameters)
request.setAttribute('jrDataSource', new JRMapCollectionDataSource(data.rows))
return 'success'
