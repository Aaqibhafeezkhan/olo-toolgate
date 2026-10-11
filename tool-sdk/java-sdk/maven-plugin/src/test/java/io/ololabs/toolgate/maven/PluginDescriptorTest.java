// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.maven;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.maven.plugin.AbstractMojo;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** The hand-written plugin.xml matches the mojo classes. Conformance: dev-server/build-plugins for Maven. */
class PluginDescriptorTest {
    @Test
    void everyGoalNamesAMojoWhoseFieldsAreItsParameters() throws Exception {
        Document doc;
        try (InputStream in = getClass().getResourceAsStream("/META-INF/maven/plugin.xml")) {
            doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
        }
        Element plugin = doc.getDocumentElement();
        assertEquals("toolgate", text(plugin, "goalPrefix"));
        assertFalse(text(plugin, "version").contains("@"), "the version is filled in at build time");
        List<String> goals = new ArrayList<>();
        NodeList mojos = plugin.getElementsByTagName("mojo");
        for (int i = 0; i < mojos.getLength(); i++) {
            Element mojo = (Element) mojos.item(i);
            goals.add(text(mojo, "goal"));
            Class<?> type = assertDoesNotThrow(() -> Class.forName(text(mojo, "implementation")));
            assertTrue(AbstractMojo.class.isAssignableFrom(type), type.getName());
            NodeList parameters = ((Element) mojo.getElementsByTagName("parameters").item(0)).getElementsByTagName("parameter");
            Element configuration = (Element) mojo.getElementsByTagName("configuration").item(0);
            for (int j = 0; j < parameters.getLength(); j++) {
                Element p = (Element) parameters.item(j);
                String name = text(p, "name");
                Field field = field(type, name);
                assertEquals(text(p, "type").replace("java.lang.", "").replace("java.util.", ""),
                        field.getType().getName().replace("java.lang.", "").replace("java.util.", ""), type.getSimpleName() + "." + name);
                assertEquals(1, configuration.getElementsByTagName(name).getLength(), "configuration of " + name);
            }
        }
        assertEquals(List.of("dev", "check", "package"), goals);
    }

    private static Field field(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                // look in the superclass
            }
        }
        throw new AssertionError(type.getName() + " has no field " + name);
    }

    private static String text(Element parent, String child) {
        return parent.getElementsByTagName(child).item(0).getTextContent().trim();
    }
}
