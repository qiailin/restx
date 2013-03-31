package restx.common;

import com.google.common.base.Charsets;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * User: xavierhanin
 * Date: 1/19/13
 * Time: 2:03 PM
 */
public class Tpl {
    private final static Pattern p = Pattern.compile("\\{([a-zA-Z]+)}");

    private final String tpl;

    public Tpl(Class clazz, String name) throws IOException {
        tpl = Resources.toString(Resources.getResource(clazz, name + ".tpl"), Charsets.UTF_8);
    }

    public String bind(final ImmutableMap<String, String> ctx) {
        Matcher m = p.matcher(tpl);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String name = m.group(1);
            m.appendReplacement(sb, Matcher.quoteReplacement(Objects.firstNonNull(ctx.get(name), name)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
