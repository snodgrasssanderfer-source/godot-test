package io.deepseekharness.mobile.companion.presence;

import java.util.List;
import io.deepseekharness.mobile.companion.presence.PresenceProtocol.Sentence;

public final class PresenceTextStreamTest {
    public static void main(String[] args) {
        PresenceTextStream stream = new PresenceTextStream();
        if (!stream.accept("你", false).isEmpty()) throw new AssertionError("unstable fragment emitted");
        if (!stream.accept("你好！后半", false).get(0).displayText.equals("你好！")) throw new AssertionError("sentence lost");
        if (!stream.accept("你好！后半", false).isEmpty()) throw new AssertionError("duplicate preview spoke twice");
        if (!stream.accept("你好！后半句", true).get(0).displayText.equals("后半句")) throw new AssertionError("final remainder lost");
        if (!stream.accept("你好！后半句", true).isEmpty()) throw new AssertionError("duplicate final spoke twice");
        PresenceTextStream changed = new PresenceTextStream(); changed.accept("第一句！", false);
        boolean rejected = false; try { changed.accept("不同！", true); } catch (IllegalArgumentException expected) { rejected = true; }
        if (!rejected) throw new AssertionError("changed prefix accepted");
        if (!new PresenceTextStream().accept("[[SILENT]]", true).isEmpty()) throw new AssertionError("silent marker spoken");
        StringBuilder b = new StringBuilder(); for (int i = 0; i < 239; i++) b.append('x'); b.append("😀tail");
        List<Sentence> parts = new PresenceTextStream().accept(b.toString(), true);
        String joined = parts.stream().map(s -> s.displayText).reduce("", String::concat);
        if (!joined.equals(b.toString()) || Character.isHighSurrogate(parts.get(0).displayText.charAt(parts.get(0).displayText.length() - 1))) throw new AssertionError("split surrogate");
        System.out.println("PASS cumulative stream / duplicate callback / prefix mutation / silent marker / surrogate boundary");
        System.out.println("RESULT 5 tests passed");
    }
}
