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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;

/**
 * Lease product helpers (STEP L1).
 *
 * <p>A lease product is a {@code FinancialProduct} whose type is {@code LEASE} or a
 * descendant of it, with lease terms in {@code FinancialProductLease}.
 */
public final class LeaseProductHelper {

    public static final String ROOT_TYPE = "LEASE";

    public static final String CLASS_FIN_TRANSFER = "LEASE_CLS_FIN_TRANS";
    public static final String CLASS_FIN_NON_TRANSFER = "LEASE_CLS_FIN_NONTR";
    public static final String CLASS_OPERATING = "LEASE_CLS_OPERATING";

    public static final String TIMING_ADVANCE = "LEASE_PAY_ADVANCE";
    public static final String REV_AT_START = "LEASE_REV_AT_START";

    /** Product type -> lease class. The root type LEASE has no class. */
    private static final Map<String, String> CLASS_BY_TYPE = UtilMisc.toMap(
            "LEASE_FIN_TRANSFER", CLASS_FIN_TRANSFER,
            "LEASE_FIN_NONTRANS", CLASS_FIN_NON_TRANSFER,
            "LEASE_OPERATING", CLASS_OPERATING);

    private LeaseProductHelper() {
    }

    /** True when the financial product type is LEASE or one of its descendants. */
    public static boolean isLeaseProductType(Delegator delegator, String financialProductTypeId)
            throws GenericEntityException {
        Set<String> visited = new HashSet<>();
        String typeId = financialProductTypeId;
        while (typeId != null && visited.add(typeId)) {
            if (ROOT_TYPE.equals(typeId)) {
                return true;
            }
            GenericValue type = EntityQuery.use(delegator).from("FinancialProductType")
                    .where("financialProductTypeId", typeId).cache().queryOne();
            if (type == null) {
                return false;
            }
            typeId = type.getString("parentTypeId");
        }
        return false;
    }

    /** Lease class implied by the product type, or null when the type has none (e.g. the root). */
    public static String leaseClassForType(String financialProductTypeId) {
        return CLASS_BY_TYPE.get(financialProductTypeId);
    }

    /** The lease terms of a product, or null when the product is not a configured lease product. */
    public static GenericValue findLeaseProduct(Delegator delegator, String productId)
            throws GenericEntityException {
        if (productId == null) {
            return null;
        }
        return EntityQuery.use(delegator).from("FinancialProductLease")
                .where("productId", productId).queryOne();
    }

    /** True when the product allows the asset type (no restriction rows = all types allowed). */
    public static boolean isAssetTypeAllowed(Delegator delegator, String productId, String fixedAssetTypeId)
            throws GenericEntityException {
        List<GenericValue> allowed = EntityQuery.use(delegator).from("FinancialProductLeaseAssetType")
                .where("productId", productId).queryList();
        if (allowed.isEmpty()) {
            return true;
        }
        for (GenericValue row : allowed) {
            if (row.getString("fixedAssetTypeId").equals(fixedAssetTypeId)) {
                return true;
            }
        }
        return false;
    }
}
