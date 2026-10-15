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

// Lease layer (STEP L1): lease product terms (FinancialProductLease).

import org.apache.ofbiz.base.util.UtilProperties
import org.apache.ofbiz.entity.GenericValue
import org.apache.ofbiz.finance.lease.LeaseProductHelper
import org.apache.ofbiz.finance.lease.LeaseTaxHelper

String label(String key, Map args = [:]) {
    return UtilProperties.getMessage('FinanceUiLabels', key, args, parameters.locale)
}

/** Request value with empty strings treated as absent (zero amounts are kept). */
Object param(String name) {
    Object value = parameters.get(name)
    return (value instanceof String && value.isEmpty()) ? null : value
}

List<String> editableFields() {
    return ['defaultMonthlyRateFactor', 'defaultCalcInterestRate', 'paymentTimingEnumId',
            'residualValueRate', 'revenueMethodEnumId', 'taxable', 'taxTimingEnumId']
}

/** Validate rates and enumerations of a lease terms value; returns an error message or null. */
String validateTerms(GenericValue terms) {
    BigDecimal residual = terms.getBigDecimal('residualValueRate')
    if (residual != null && (residual.signum() < 0 || residual.compareTo(BigDecimal.ONE) >= 0)) {
        return label('FinanceLeaseResidualRateInvalid')
    }
    BigDecimal factor = terms.getBigDecimal('defaultMonthlyRateFactor')
    if (factor != null && factor.signum() <= 0) {
        return label('FinanceLeaseRateFactorInvalid')
    }
    BigDecimal calcRate = terms.getBigDecimal('defaultCalcInterestRate')
    if (calcRate != null && calcRate.signum() < 0) {
        return label('FinanceLeaseCalcRateInvalid')
    }
    Map enumTypes = [paymentTimingEnumId: 'LEASE_PAY_TIMING', revenueMethodEnumId: 'LEASE_REV_METHOD',
                     leaseClassEnumId: 'LEASE_CLASS']
    for (entry in enumTypes) {
        String enumId = terms.getString(entry.key)
        GenericValue enumValue = enumId ? from('Enumeration').where('enumId', enumId).cache().queryOne() : null
        if (enumValue == null || enumValue.enumTypeId != entry.value) {
            return label('FinanceLeaseEnumInvalid', [field: entry.key, value: enumId])
        }
    }
    // STEP L12: operating leases use their own revenue method and per-installment tax only.
    boolean operating = LeaseProductHelper.CLASS_OPERATING == terms.leaseClassEnumId
    if (operating != (LeaseProductHelper.REV_OPERATING == terms.revenueMethodEnumId)) {
        return label('FinanceLeaseRevenueMethodClassMismatch', [revenueMethodEnumId: terms.revenueMethodEnumId,
                                                                leaseClassEnumId: terms.leaseClassEnumId])
    }
    if (operating && terms.taxTimingEnumId == LeaseTaxHelper.TIMING_AT_START) {
        return label('FinanceLeaseOperatingTaxTimingInvalid')
    }
    if (!(terms.taxable in ['Y', 'N'])) {
        return label('FinanceLeaseEnumInvalid', [field: 'taxable', value: terms.taxable])
    }
    if (terms.taxTimingEnumId) {
        GenericValue timing = from('Enumeration').where('enumId', terms.taxTimingEnumId).cache().queryOne()
        if (timing == null || timing.enumTypeId != 'LEASE_TAX_TIMING') {
            return label('FinanceLeaseEnumInvalid', [field: 'taxTimingEnumId', value: terms.taxTimingEnumId])
        }
    }
    return null
}

/** The lease class implied by the product type, checked against an explicitly requested class. */
Map resolveLeaseClass(GenericValue financialProduct, String requestedClass, String currentClass = null) {
    String typeId = financialProduct.financialProductTypeId
    if (!LeaseProductHelper.isLeaseProductType(delegator, typeId)) {
        return [error: label('FinanceLeaseProductTypeRequired', [productId: financialProduct.productId])]
    }
    String derived = LeaseProductHelper.leaseClassForType(typeId)
    if (derived != null && requestedClass != null && derived != requestedClass) {
        return [error: label('FinanceLeaseClassMismatch', [leaseClassEnumId: requestedClass, financialProductTypeId: typeId])]
    }
    String leaseClass = derived ?: (requestedClass ?: currentClass)
    if (leaseClass == null) {
        return [error: label('FinanceLeaseClassRequired', [financialProductTypeId: typeId])]
    }
    return [leaseClassEnumId: leaseClass]
}

Map createFinancialProductLease() {
    GenericValue financialProduct = from('FinancialProduct').where('productId', parameters.productId).queryOne()
    if (financialProduct == null) {
        return error(label('FinanceRecordNotFound', [entityName: 'FinancialProduct', id: parameters.productId]))
    }
    if (from('FinancialProductLease').where('productId', parameters.productId).queryOne() != null) {
        return error(label('FinanceLeaseTermsExist', [productId: parameters.productId]))
    }
    Map leaseClass = resolveLeaseClass(financialProduct, (String) param('leaseClassEnumId'))
    if (leaseClass.error) {
        return error(leaseClass.error)
    }
    GenericValue terms = makeValue('FinancialProductLease', [productId: parameters.productId])
    editableFields().each { String f -> terms.set(f, param(f)) }
    terms.leaseClassEnumId = leaseClass.leaseClassEnumId
    terms.paymentTimingEnumId = terms.paymentTimingEnumId ?: LeaseProductHelper.TIMING_ADVANCE
    terms.revenueMethodEnumId = terms.revenueMethodEnumId ?: LeaseProductHelper.defaultRevenueMethod(terms.leaseClassEnumId)
    terms.taxable = terms.taxable ?: 'Y'
    if (terms.residualValueRate == null && LeaseProductHelper.CLASS_FIN_NON_TRANSFER == terms.leaseClassEnumId) {
        terms.residualValueRate = BigDecimal.ZERO
    }
    String invalid = validateTerms(terms)
    if (invalid) {
        return error(invalid)
    }
    terms.create()
    return success()
}

Map updateFinancialProductLease() {
    GenericValue terms = from('FinancialProductLease').where('productId', parameters.productId).queryOne()
    if (terms == null) {
        return error(label('FinanceRecordNotFound', [entityName: 'FinancialProductLease', id: parameters.productId]))
    }
    GenericValue financialProduct = from('FinancialProduct').where('productId', parameters.productId).queryOne()
    Map leaseClass = resolveLeaseClass(financialProduct, (String) param('leaseClassEnumId'), terms.leaseClassEnumId)
    if (leaseClass.error) {
        return error(leaseClass.error)
    }
    editableFields().each { String f ->
        if (param(f) != null) {
            terms.set(f, param(f))
        }
    }
    terms.leaseClassEnumId = leaseClass.leaseClassEnumId
    String invalid = validateTerms(terms)
    if (invalid) {
        return error(invalid)
    }
    terms.store()
    return success()
}
