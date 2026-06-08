package restudio.resync.text;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ReTextTemplateTest {
    @Test
    void frameModeUsesFrameMillis() {
        JsonObject json = new JsonObject();
        json.addProperty("id", "pulse");
        json.addProperty("frameMillis", 100);
        JsonArray frames = new JsonArray();
        frames.add("One");
        frames.add("Two");
        frames.add("Three");
        json.add("frames", frames);

        ReTextService.ReTextTemplate template = ReTextService.ReTextTemplate.fromJson(json);

        assertNotNull(template);
        assertEquals("One", template.frame(null, null, 0));
        assertEquals("Two", template.frame(null, null, 100));
        assertEquals("Three", template.frame(null, null, 200));
        assertEquals("One", template.frame(null, null, 300));
    }

    @Test
    void textFallbackCreatesSingleFrameTemplate() {
        JsonObject json = new JsonObject();
        json.addProperty("id", "single");
        json.addProperty("text", "Static");

        ReTextService.ReTextTemplate template = ReTextService.ReTextTemplate.fromJson(json);

        assertNotNull(template);
        assertEquals("Static", template.frame(null, null, 0));
        assertEquals("Static", template.frame(null, null, 1000));
    }

    @Test
    void animationModesProduceExpectedFrames() {
        JsonObject typing = new JsonObject();
        typing.addProperty("id", "typing");
        typing.addProperty("mode", "typing");
        typing.addProperty("frameMillis", 50);
        typing.addProperty("text", "Chat");
        ReTextService.ReTextTemplate typingTemplate = ReTextService.ReTextTemplate.fromJson(typing);

        assertNotNull(typingTemplate);
        assertEquals("", typingTemplate.frame(null, null, 0));
        assertEquals("C", typingTemplate.frame(null, null, 50));
        assertEquals("Cha", typingTemplate.frame(null, null, 150));

        JsonObject scroll = new JsonObject();
        scroll.addProperty("id", "scroll");
        scroll.addProperty("mode", "scroll");
        scroll.addProperty("frameMillis", 100);
        scroll.addProperty("text", "abcdef");
        scroll.addProperty("width", 3);
        ReTextService.ReTextTemplate scrollTemplate = ReTextService.ReTextTemplate.fromJson(scroll);

        assertNotNull(scrollTemplate);
        assertEquals("abc", scrollTemplate.frame(null, null, 0));
        assertEquals("bcd", scrollTemplate.frame(null, null, 100));

        JsonObject pulse = new JsonObject();
        pulse.addProperty("id", "pulse");
        pulse.addProperty("mode", "pulse");
        pulse.addProperty("frameMillis", 100);
        pulse.addProperty("text", "Name");
        pulse.addProperty("color", "red");
        pulse.addProperty("secondaryColor", "blue");
        ReTextService.ReTextTemplate pulseTemplate = ReTextService.ReTextTemplate.fromJson(pulse);

        assertNotNull(pulseTemplate);
        assertEquals("<red>Name</red>", pulseTemplate.frame(null, null, 0));
        assertEquals("<blue>Name</blue>", pulseTemplate.frame(null, null, 100));
    }

    @Test
    void scrollModePreservesMiniMessageTags() {
        JsonObject scroll = new JsonObject();
        scroll.addProperty("id", "styled_scroll");
        scroll.addProperty("mode", "scroll");
        scroll.addProperty("frameMillis", 100);
        scroll.addProperty("text", "<red>abcdef</red>");
        scroll.addProperty("width", 3);
        ReTextService.ReTextTemplate scrollTemplate = ReTextService.ReTextTemplate.fromJson(scroll);

        assertNotNull(scrollTemplate);
        assertEquals("<red>abc</red>", scrollTemplate.frame(null, null, 0));
        assertEquals("<red>bcd</red>", scrollTemplate.frame(null, null, 100));
        assertEquals("<red>cde</red>", scrollTemplate.frame(null, null, 200));
        assertEquals("<red>def</red>", scrollTemplate.frame(null, null, 300));
    }
}
