// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.adapter;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDirFactory;
import org.junit.jupiter.api.extension.*;
import org.junit.jupiter.api.condition.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.LINUX)
final class InitializationManifestTest {
    // The production custody boundary rejects world-writable /tmp ancestors.
    public static class PrivateDirectory implements TempDirFactory {
        public Path createTempDirectory(AnnotatedElementContext element,ExtensionContext context) throws java.io.IOException {
            return Files.createTempDirectory(Path.of(System.getProperty("user.home")),"initialization-test-",
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
        }
    }
    @TempDir(factory=PrivateDirectory.class) Path temp;
    private final ContractCodec codec=new ContractCodec();
    private ObjectNode manifest() throws Exception {
        var review=Files.createDirectories(temp.resolve("review"));
        Files.writeString(review.resolve("packet.json"),"{}");Files.writeString(review.resolve("trust.json"),"{}");
        return (ObjectNode)codec.configuration("{\"formatVersion\":1,\"defaultTenantAlias\":\"default\",\"tenants\":[{\"alias\":\"default\",\"name\":\"Default\",\"hosts\":[\"Example.COM\"],\"administratorProvisioningRef\":\"review\"}]}",false);
    }
    private InitializationManifest load(ObjectNode node) throws Exception {
        var file=temp.resolve("manifest.json");Files.writeString(file,codec.json(node));return InitializationManifest.load(file,codec);
    }
    @Test void boundedInputsAndRepeatedIncludesCannotExceedTheAggregateBudget() throws Exception {
        var node=manifest();var layer=temp.resolve("large.json");
        Files.writeString(layer,"{\"formatVersion\":2,\"settings\":{\"gateway\":{\"gatewayName\":\""+"a".repeat(1024*1024)+"\"}}}");
        node.putArray("commonImports").add("large.json");assertEquals("example.com",load(node).tenants().getFirst().hosts().getFirst());
        var includes=node.putArray("commonImports");for(int i=0;i<16;i++)includes.add("large.json");
        assertThrows(IllegalArgumentException.class,()->load(node),"Repeated reads count toward the 16 MiB aggregate limit");
        Files.writeString(layer,"a".repeat(2097153));includes.removeAll().add("large.json");
        assertThrows(IllegalArgumentException.class,()->load(node),"Per-file bounds also apply");
    }
    @Test void duplicatesMissingDefaultUnknownFieldsAndPathEscapesAreRejected() throws Exception {
        var base=manifest();assertEquals("default",load(base).defaultAlias());
        var duplicate=base.deepCopy();duplicate.withArray("tenants").add(duplicate.withArray("tenants").get(0).deepCopy());
        assertThrows(RuntimeException.class,()->load(duplicate));
        var hosts=base.deepCopy();var second=hosts.withArray("tenants").get(0).deepCopy();((ObjectNode)second).put("alias","other");hosts.withArray("tenants").add(second);
        assertThrows(RuntimeException.class,()->load(hosts),"Normalized host ownership is unique");
        var missing=base.deepCopy();missing.put("defaultTenantAlias","missing");assertThrows(RuntimeException.class,()->load(missing));
        var unknown=base.deepCopy();unknown.put("overwrite",true);assertThrows(RuntimeException.class,()->load(unknown));
        var badVersion=base.deepCopy();badVersion.put("formatVersion","1");assertThrows(RuntimeException.class,()->load(badVersion));
        var escape=base.deepCopy();escape.putArray("commonImports").add("../outside.json");assertThrows(RuntimeException.class,()->load(escape));
        var absent=base.deepCopy();absent.putArray("commonImports").add("missing.json");assertThrows(java.io.IOException.class,()->load(absent));
    }
}
