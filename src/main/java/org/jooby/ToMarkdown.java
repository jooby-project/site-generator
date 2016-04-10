package org.jooby;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.google.common.base.Splitter;
import com.typesafe.config.ConfigFactory;

public class ToMarkdown {

  private static CharSequence $NL = "_NL";

  public static void main(final String[] args) throws Exception {
    CompilationUnit unit = JavaParser.parse(new File(
        "../jooby-project/jooby-sitemap/src/main/java/org/jooby/sitemap/Sitemap.java"));

    System.out.println(toMd(unit));

  }

  public static String toMd(final CompilationUnit unit) throws Exception {
    String sourceCode = unit.toString();
    String javadoc = unit.getTypes().get(0).getComment().toString();
    return toMd(javadoc, sourceCode);
  }

  public static String toMd(final String html, final String sourceCode) throws Exception {
    return toMd(html, sourceCode, 0);
  }

  public static String toMd(final String html, final String sourceCode, final int hlevel)
      throws Exception {
    String javadoc = html;
    javadoc = javadoc.replace("/**", "\n")
        .replace("*/", "\n")
        .replace(" * ", "\n")
        .replace(" *", "\n")
        .replace("<pre>{@code", "<pre>")
        .replace("}</pre>", "</pre>");

    javadoc = joinplines(javadoc);

    javadoc = preservePre(javadoc);

    javadoc = dependency(javadoc);

    if (hlevel > 0) {
      javadoc = renameheading(javadoc, hlevel);
    }

    javadoc = heading(javadoc, 1);
    javadoc = heading(javadoc, 2);
    javadoc = heading(javadoc, 3);
    javadoc = heading(javadoc, 4);

    javadoc = p(javadoc);
    javadoc = ul(javadoc);
    javadoc = code(javadoc);
    javadoc = pre(javadoc);
    javadoc = strong(javadoc);
    // javadoc = apidoc(javadoc, sourceCode);
    javadoc = attr(javadoc, "@author");
    javadoc = nl(javadoc);

    return javadoc;
  }

  private static String renameheading(final String javadoc, final int hlevel) {
    String html = javadoc;
    for (int i = 5; i >= hlevel; i--) {
      html = html.replace("<h" + i + ">", "<h" + (i + 1) + ">")
          .replace("</h" + i + ">", "</h" + (i + 1) + ">");
    }
    return html;
  }

  private static String dependency(final String javadoc) {
    Document doc = Jsoup.parseBodyFragment(javadoc);
    Element h1 = doc.select("h1").first();
    Element h2 = doc.select("h2").first();
    if (h1 != null && h2 != null) {
      h2.before(
          "<h2>dependency</h2>\n<pre>\n&lt;dependency&gt;\n  &lt;groupId&gt;org.jooby&lt;/groupId&gt;\n  &lt;artifactId&gt;jooby-"
              + h1.text().trim().toLowerCase()
              + "&lt;/artifactId&gt;\n  &lt;version&gt;{{version}}&lt;/version&gt;\n&lt;/dependency&gt;\n</pre>\n");
      return doc.body().html();
    }
    return javadoc;
  }

  private static String preservePre(final String javadoc) {
    Document doc = Jsoup.parseBodyFragment(javadoc);
    doc.select("pre").forEach(pre -> {
      String html = Splitter.on("\n").omitEmptyStrings().splitToList(pre.html()).stream()
          .map(line -> {
            String tline = line.trim();
            int diff = line.length() - tline.length();
            if (diff == 2 && tline.length() > 0 && !(tline.startsWith("//"))) {
              return line + $NL;
            }
            return line;
          }).collect(Collectors.joining($NL));
      pre.html($NL + html + $NL);
    });
    return doc.body().html();
  }

  private static String joinplines(String javadoc) {
    Document doc = Jsoup.parse(javadoc);
    Elements p = doc.select("p");
    for (Element element : p) {
      element.html(element.html().replace("\n", " "));
    }
    javadoc = doc.body().html();
    return javadoc;
  }

  private static String nl(final String javadoc) {
    return javadoc.replaceAll("\\n+", "\n").replace("\n ", "\n")
        .replace($NL, "\n")
        .trim();
  }

  private static String apidoc(final String javadoc, final String sourceCode) {
    Matcher matcher = Pattern.compile("\\{@link ([^}]+)\\}").matcher(javadoc);
    if (matcher.find()) {
      String classname = matcher.group(1);
      matcher = Pattern.compile("import\\s+((.*))\\." + classname).matcher(sourceCode);
      if (matcher.find()) {
        String pkg = matcher.group(1);
        if (pkg.equals("org.jooby")) {
          return javadoc.replaceAll("\\{@link ([^}]+)\\}", "[$1]({{defdocs}}/$1.html)");
        }
        return javadoc.replaceAll("\\{@link ([^}]+)\\}", "```" + pkg + ".$1```");
      } else {
        return javadoc.replaceAll("\\{@link ([^}]+)\\}", "[$1]($1)");
      }
    }
    return javadoc;
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
    String html = javadoc
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("{@literal ->}", "->");
    List<String> types = typesFor(html.replace($NL, "\n"));
    for (String type : types) {
      html = html.replaceFirst("<pre>", "```" + type);
    }
    return html.replace("</pre>", "```\n");
  }

  private static List<String> typesFor(final String source) {
    List<String> types = new ArrayList<>();
    Jsoup.parseBodyFragment(source).select("pre").forEach(pre -> {
      String s = pre.html().trim();
      if (s.startsWith("{@code")) {
        s = s.substring("{@code".length(), s.lastIndexOf("}")).trim();
      }
      types.add(typeFor(s));
    });
    return types;
  }

  private static String typeFor(final String source) {
    if (isConf(source)) {
      return "";
    }
    if (isXml(source)) {
      return "xml";
    }
    return "java";
  }

  private static boolean isConf(final String source) {
    try {
      ConfigFactory.parseReader(new StringReader(source));
      return true;
    } catch (Exception ex) {
      return false;
    }
  }

  private static boolean isXml(final String source) {
    try {
      DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
      DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
      dBuilder.setErrorHandler(new ErrorHandler() {

        @Override
        public void warning(final SAXParseException exception) throws SAXException {
        }

        @Override
        public void fatalError(final SAXParseException exception) throws SAXException {
        }

        @Override
        public void error(final SAXParseException exception) throws SAXException {
        }
      });
      InputStream stream = new ByteArrayInputStream(source.getBytes());
      dBuilder.parse(stream);
      return true;
    } catch (Exception ex) {
      return false;
    }
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
