package org.jooby;

import java.io.File;
import java.io.IOException;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseException;
import com.github.javaparser.ast.CompilationUnit;

public class ToMarkdown {

  public static void main(final String[] args) throws ParseException, IOException {
    CompilationUnit unit = JavaParser.parse(new File(
        "/Users/edgar/Source/jooby-project/jooby-assets-rollup/src/main/java/org/jooby/assets/Rollup.java"));
    String javadoc = unit.getTypes().get(0).getComment().toString();
    javadoc = javadoc.replace("/**", "").replace("*/", "").replace(" * ", "").replace(" *", "");

    javadoc = joinplines(javadoc);

    javadoc = heading(javadoc, 1);
    javadoc = heading(javadoc, 2);
    javadoc = heading(javadoc, 3);
    javadoc = heading(javadoc, 4);
    javadoc = heading(javadoc, 5);
    javadoc = p(javadoc);
    javadoc = ul(javadoc);
    javadoc = code(javadoc);
    javadoc = pre(javadoc);
    javadoc = strong(javadoc);
    javadoc = apidoc(javadoc);
    javadoc = attr(javadoc, "@author");
    javadoc = nl(javadoc);
    System.out.println(javadoc);

  }

  private static String joinplines(String javadoc) {
    Document doc = Jsoup.parse(javadoc);
    Elements p = doc.select("p");
    for (Element element : p) {
      element.html(element.html().replace("\n",  " "));
    }
    javadoc = doc.body().html();
    return javadoc;
  }

  private static String nl(final String javadoc) {
    return javadoc.replaceAll("\\n+", "\n").replace("\n ", "\n").trim();
  }

  private static String apidoc(final String javadoc) {
    return javadoc.replaceAll("\\{@link ([^}]+)\\}", "[$1]({{defdocs}}/assets/$1.html)");
  }

  private static String heading(final String javadoc, final int level) {
    String head = fill("#", level);
    return javadoc.replace("<h" + level + ">", head + " ").replace("</h" + level + ">", "\n");
  }

  private static String attr(final String javadoc, final String tag) {
    return javadoc.replaceAll(tag + " [^\n]+", "");
  }

  private static String p(final String javadoc) {
    return javadoc.replace("<p>", "\n").replace("</p>", "\n");
  }

  private static String code(final String javadoc) {
    return javadoc.replace("<code>", "```").replace("</code>", "```");
  }

  private static String strong(final String javadoc) {
    return javadoc.replace("<strong>", "**").replace("</strong>", "**");
  }

  private static String pre(final String javadoc) {
    return javadoc.replace("<pre>", "\n```").replace("</pre>", "```\n").replace("&lt;", "<")
        .replace("&gt;", ">").replace("@{literal ->}", "->");
  }

  private static String ul(final String javadoc) {
    return javadoc.replace("<ul>", "").replace("</ul>", "").replace("<li>", "* ").replace("</li>",
        "");
  }

  private static String fill(final String value, final int level) {
    StringBuilder buff = new StringBuilder();
    for (int i = 0; i < level; i++) {
      buff.append(value);
    }
    return buff.toString();
  }

}
