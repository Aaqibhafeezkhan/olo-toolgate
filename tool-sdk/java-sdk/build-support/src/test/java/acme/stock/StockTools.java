// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package acme.stock;

import io.ololabs.toolgate.sdk.annotations.EffectSafety;
import io.ololabs.toolgate.sdk.annotations.ErrorDecl;
import io.ololabs.toolgate.sdk.annotations.Example;
import io.ololabs.toolgate.sdk.annotations.Global;
import io.ololabs.toolgate.sdk.annotations.Param;
import io.ololabs.toolgate.sdk.annotations.ServerTool;
import io.ololabs.toolgate.sdk.annotations.ToolPackage;
import io.ololabs.toolgate.sdk.annotations.ValueType;
import io.ololabs.toolgate.sdk.context.ToolContext;
import io.ololabs.toolgate.sdk.errors.ToolBusinessError;
import java.util.Map;

/** Tools for the build library tests: one example passes, one expects the wrong error. */
@ToolPackage(id = "acme.stock", name = "Stock", publisher = "Acme")
@Global(name = "Warehouse", type = ValueType.STRING)
public class StockTools {
    @ServerTool(name = "stock.level", title = "Level", description = "Reads a stock level.", effect = EffectSafety.READ_ONLY,
            errors = @ErrorDecl(code = "UNKNOWN_SKU", description = "No such SKU"))
    @Example(arguments = "{\"sku\":\"a-1\"}", expectedOutput = "{\"sku\":\"a-1\",\"level\":4,\"warehouse\":\"north\"}")
    @Example(arguments = "{\"sku\":\"zz\"}", expectedError = "OUT_OF_STOCK")
    public Map<String, Object> level(@Param(description = "SKU") String sku, ToolContext context) {
        if (sku.equals("zz")) throw new ToolBusinessError("UNKNOWN_SKU", "No SKU zz");
        return Map.of("sku", sku, "level", 4, "warehouse", StockToolsVars.global(context).warehouse());
    }
}
