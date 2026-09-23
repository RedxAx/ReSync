package restudio.resync.flow.identity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SegmentedIdentityTest {
    @Test
    void preservesPublishedIdentityGrammar() {
        Pattern owner = Pattern.compile("[a-z][a-z0-9]{0,31}(?:[.-][a-z][a-z0-9]{0,31})*");
        Pattern local = Pattern.compile("[a-z][a-z0-9]{0,31}(?:[._-][a-z0-9][a-z0-9]{0,31})*");
        List<String> values = new ArrayList<>(List.of("ability-effect.particle-burst.ability-spawn-particle-burst",
            "", "a", "1", "a.1", "a_b", "a..b", "a-", "a".repeat(32), "a".repeat(33),
            "a." + "1".repeat(32), "a." + "1".repeat(33), "a.".repeat(63) + "ab", "a.".repeat(64) + "a"));
        Random random = new Random(71293);
        String characters = "abcd0123._-AZ /é";
        for (int sample = 0; sample < 10000; sample++) {
            StringBuilder value = new StringBuilder();
            int length = random.nextInt(140);
            for (int index = 0; index < length; index++) value.append(characters.charAt(random.nextInt(characters.length())));
            values.add(value.toString());
        }
        for (String value : values) {
            assertEquals(value.length() <= 128 && owner.matcher(value).matches(), accepts(value, true), "owner: " + value);
            assertEquals(value.length() <= 128 && local.matcher(value).matches(), accepts(value, false), "local: " + value);
        }
    }

    private boolean accepts(String value, boolean owner) {
        try {
            if (owner) OwnerId.of(value);
            else CapabilityId.of(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
