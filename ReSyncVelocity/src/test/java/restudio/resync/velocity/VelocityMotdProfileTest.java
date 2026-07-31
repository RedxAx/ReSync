package restudio.resync.velocity;

import org.junit.jupiter.api.Test;
import restudio.resync.network.NetworkPayloads;
import restudio.resync.network.NetworkResource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityMotdProfileTest {
    @Test
    void selectsHighestPriorityEnabledProfile() {
        NetworkResource disabled = resource("disabled", """
                {"enabled":false,"priority":100,"line1":"Disabled"}
                """);
        NetworkResource fallback = resource("fallback", """
                {"enabled":true,"priority":1,"line1":"Fallback"}
                """);
        NetworkResource primary = resource("primary", """
                {"enabled":true,"priority":10,"line1":"Primary","line2":"Online","playerCountMode":"fixed","onlinePlayers":12,"maxPlayers":50}
                """);

        VelocityMotdProfile profile = VelocityMotdProfile.select(List.of(disabled, fallback, primary)).orElseThrow();

        assertEquals("fixed", profile.playerCountMode());
        assertEquals(12, profile.onlinePlayers().intValue());
        assertEquals(50, profile.maxPlayers().intValue());
    }

    @Test
    void ignoresMalformedAndDeletedProfiles() {
        NetworkResource malformed = resource("malformed", "{");
        NetworkResource deleted = deleted("deleted");

        assertTrue(VelocityMotdProfile.select(List.of(malformed, deleted)).isEmpty());
    }

    @Test
    void preservesLiveCountsWhenFixedValuesAreMissing() {
        VelocityMotdProfile profile = VelocityMotdProfile.select(List.of(resource("profile", """
                {"enabled":true,"line1":"Network","playerCountMode":"fixed"}
                """))).orElseThrow();

        assertNull(profile.onlinePlayers());
        assertNull(profile.maxPlayers());
    }

    @Test
    void loadsValidInlineFavicon() throws Exception {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        String icon = Base64.getEncoder().encodeToString(output.toByteArray());
        VelocityMotdProfile profile = VelocityMotdProfile.select(List.of(resource("profile", """
                {"enabled":true,"line1":"Network","iconData":"%s"}
                """.formatted(icon)))).orElseThrow();

        assertNotNull(profile.favicon());
    }

    private NetworkResource resource(String id, String json) {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        return new NetworkResource("network", "motd_profile", id, 1, NetworkPayloads.sha256(payload), payload, false, "velocity", 1);
    }

    private NetworkResource deleted(String id) {
        byte[] payload = new byte[0];
        return new NetworkResource("network", "motd_profile", id, 1, NetworkPayloads.sha256(payload), payload, true, "velocity", 1);
    }
}
