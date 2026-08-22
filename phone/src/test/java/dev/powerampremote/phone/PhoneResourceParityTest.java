package dev.powerampremote.phone;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PhoneResourceParityTest {
    private static final Pattern PLACEHOLDER = Pattern.compile("%(?:\\d+\\$)?[a-zA-Z]");
    private static final Pattern CYRILLIC = Pattern.compile("\\p{IsCyrillic}");

    @Test
    public void englishAndRussianTranslatableResourcesHaveMatchingKeysAndFormats()
            throws Exception {
        ResourceSet english = readResourceSet(resourcePath("values/strings.xml"));
        ResourceSet russian = readResourceSet(resourcePath("values-ru/strings.xml"));

        assertEquals(english.strings.keySet(), russian.strings.keySet());
        assertEquals(english.arrays.keySet(), russian.arrays.keySet());
        for (String key : english.strings.keySet()) {
            assertEquals(
                    "placeholder mismatch for " + key,
                    placeholders(english.strings.get(key)),
                    placeholders(russian.strings.get(key))
            );
        }
        for (String key : english.arrays.keySet()) {
            List<String> englishItems = english.arrays.get(key);
            List<String> russianItems = russian.arrays.get(key);
            assertEquals("array length mismatch for " + key,
                    englishItems.size(), russianItems.size());
            for (int index = 0; index < englishItems.size(); index++) {
                assertEquals(
                        "placeholder mismatch for " + key + "[" + index + "]",
                        placeholders(englishItems.get(index)),
                        placeholders(russianItems.get(index))
                );
            }
        }
    }

    @Test
    public void englishFallbackContainsNoCyrillicUserText() throws Exception {
        ResourceSet english = readResourceSet(resourcePath("values/strings.xml"));
        for (Map.Entry<String, String> entry : english.strings.entrySet()) {
            assertFalse(entry.getKey(), CYRILLIC.matcher(entry.getValue()).find());
        }
        for (Map.Entry<String, List<String>> entry : english.arrays.entrySet()) {
            for (String item : entry.getValue()) {
                assertFalse(entry.getKey(), CYRILLIC.matcher(item).find());
            }
        }
    }

    @Test
    public void androidLocaleConfigAdvertisesOnlyEnglishAndRussian() throws Exception {
        Document document = readDocument(resourcePath("xml/locales_config.xml"));
        NodeList locales = document.getElementsByTagName("locale");
        Set<String> tags = new HashSet<>();
        for (int index = 0; index < locales.getLength(); index++) {
            Element locale = (Element) locales.item(index);
            tags.add(locale.getAttributeNS(
                    "http://schemas.android.com/apk/res/android",
                    "name"
            ));
        }
        assertEquals(Set.of("en", "ru"), tags);
    }

    @Test
    public void onlyProductNamesAndUrlsAreNonTranslatable() throws Exception {
        Document document = readDocument(resourcePath("values/strings.xml"));
        Set<String> names = new HashSet<>();
        NodeList children = document.getDocumentElement().getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (!(node instanceof Element)) continue;
            Element element = (Element) node;
            if ("false".equals(element.getAttribute("translatable"))) {
                names.add(element.getAttribute("name"));
            }
        }
        assertEquals(Set.of(
                "app_name",
                "about_product_name",
                "repository_url",
                "third_party_notices_url"
        ), names);
    }

    private static ResourceSet readResourceSet(Path path) throws Exception {
        Document document = readDocument(path);
        Map<String, String> strings = new HashMap<>();
        Map<String, List<String>> arrays = new HashMap<>();
        NodeList children = document.getDocumentElement().getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (!(node instanceof Element)) continue;
            Element element = (Element) node;
            if ("false".equals(element.getAttribute("translatable"))) continue;
            String name = element.getAttribute("name");
            if ("string".equals(element.getTagName())) {
                assertTrue("duplicate string " + name, strings.put(
                        name,
                        element.getTextContent().trim()
                ) == null);
            } else if ("string-array".equals(element.getTagName())) {
                List<String> items = new ArrayList<>();
                NodeList itemNodes = element.getElementsByTagName("item");
                for (int itemIndex = 0; itemIndex < itemNodes.getLength(); itemIndex++) {
                    items.add(itemNodes.item(itemIndex).getTextContent().trim());
                }
                assertTrue("duplicate array " + name, arrays.put(name, items) == null);
            }
        }
        return new ResourceSet(strings, arrays);
    }

    private static Document readDocument(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(path.toFile());
    }

    private static List<String> placeholders(String value) {
        List<String> result = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) result.add(matcher.group());
        return result;
    }

    private static Path resourcePath(String relative) {
        Path workingDirectory = Paths.get(System.getProperty("user.dir"));
        Path rootProject = workingDirectory.resolve("phone/src/main/res").resolve(relative);
        if (Files.isRegularFile(rootProject)) return rootProject;
        Path moduleProject = workingDirectory.resolve("src/main/res").resolve(relative);
        if (Files.isRegularFile(moduleProject)) return moduleProject;
        throw new IllegalStateException("Phone resource not found: " + relative);
    }

    private static final class ResourceSet {
        final Map<String, String> strings;
        final Map<String, List<String>> arrays;

        ResourceSet(Map<String, String> strings, Map<String, List<String>> arrays) {
            this.strings = strings;
            this.arrays = arrays;
        }
    }
}
