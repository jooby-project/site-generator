package org.jooby;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.jruby.embed.PathType;
import org.jruby.embed.ScriptingContainer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Tag;

import com.google.common.base.Splitter;

public class MarkdownToHtml {

  ScriptingContainer rubyEnv = new ScriptingContainer();

  Object script = rubyEnv.runScriptlet(PathType.CLASSPATH, "to_html.rb");

  public Page toHtml(final String filename, final String text) {
    String input = text;
    boolean resetH1 = !filename.equals("index.md");
    switch (filename) {
      case "quickstart/index.md":
        input = input.replaceFirst("quickstart", "start");
        break;
      case "doc/index.md":
        input = input.replaceFirst("documentation\n=====", "");
        break;
    }
    Document doc = Jsoup
        .parseBodyFragment(rubyEnv.callMethod(script, "md_to_html", input).toString());

    if (resetH1) {
      Consumer<Integer> resetH = level -> {
        for (Element h : doc.select("h" + level)) {
          h.replaceWith(new Element(Tag.valueOf("h" + (level + 1)), "").text(h.text()));
        }
      };
      resetH.accept(4);
      resetH.accept(3);
      resetH.accept(2);
      resetH.accept(1);
    }
    String raw = doc.select("body").html();

    StringBuilder toc = new StringBuilder();
    String title = null;
    String sdesc = null;
    toc.append("<ul>");
    String active = "active";
    for (Element h2 : doc.select("h2")) {
      StringBuilder html = new StringBuilder();
      String header = h2.text();
      if (title == null) {
        title = h2.text();
        Element p = doc.select("p").first();
        if (p != null) {
          sdesc = Splitter.on('.').splitToList(p.text()).stream().findFirst().get().trim();
        }
      }
      String id = id(header);
      html.append("<div class=\"datalist-title ").append(active).append("\">\n")
          .append(h2)
          .append("\n</div>\n");

      html.append("<div class=\"datalist-content\">\n");

      Element sibling = h2.nextElementSibling();
      StringBuilder subtoc = new StringBuilder();
      while (sibling != null && !sibling.tagName().equals("h2")) {
        if (sibling.tagName().equals("h3")) {
          subtoc.append("\n<li>\n<a href=\"#").append(id + "-" + id(sibling.text()))
              .append("\">").append(sibling.text())
              .append("</a></li>");
          sibling.attr("id", id + "-" + id(sibling.text()));
        } else if (sibling.tagName().equals("h4")) {
          sibling.attr("id", id + "-" + id(sibling.text()));
        }
        html.append(sibling);
        Element remove = sibling;
        sibling = sibling.nextElementSibling();
        remove.remove();
      }
      html.append("</div>\n");
      Element section = new Element(Tag.valueOf("div"), "")
          .addClass("datalist")
          .attr("id", id)
          .html(html.toString());
      h2.replaceWith(section);
      toc.append("\n<li class=\"").append(active)
          .append("\">\n<a href=\"#")
          .append(id).append("\">")
          .append(header)
          .append("</a>");
      if (subtoc.length() > 0) {
        toc.append("<ul>").append(subtoc).append("</ul>");
      }
      toc.append("</li>");
      active = "";
    }

    doc.select("a").forEach(it -> {
      String href = it.attr("href");
      boolean abs = href.startsWith("http://") || href.startsWith("https://");
      if (abs && !href.startsWith("http://jooby.org")) {
        it.attr("target", "_blank");
      }
      if (href.contains("/apidocs")) {
        it.attr("target", "_blank");
      }
    });

    doc.select(".highlighter-rouge").forEach(it -> it.addClass("codehilite"));

    toc.append("\n</ul>");
    String bcm = "<span class=\"cm\">";
    String format = "<span class=\"cm\">%1$s</span>";
    String ecm = "</span>";
    // fix multi-line comments
    List<String> html = Splitter.on("\n").splitToList(doc.select("body").html()).stream()
        .map(line -> {
          String tline = line.trim();
          if (tline.startsWith(bcm)) {
            StringBuilder spaces = new StringBuilder();
            for (int i = 0; i < line.length() - tline.length(); i++) {
              spaces.append(" ");
            }
            String cml = tline.substring(bcm.length(), tline.length() - ecm.length());
            if (cml.startsWith("/**")) {
              cml = cml.substring("/**".length(), cml.length() - "*/".length());
              cml = Splitter.onPattern("\\s*\\*\\s+").splitToList(cml).stream().map(cl -> {
                return spaces.toString() + " " + String.format(format, "* " + cl).trim() + "\n";
              }).collect(Collectors.joining("\n",
                  spaces.toString() + String.format(format, "/**") + "\n",
                  spaces.toString() + " " + String.format(format, "*/")));
              return Splitter.on("\n").splitToList(cml).stream()
                  .filter(l -> l.trim().length() > 0)
                  .collect(Collectors.joining("\n"));
            }
          }
          return line;
        })
        .collect(Collectors.toList());
    String stitle = null;
    if (filename.contains("doc")) {
      List<String> spath = Splitter.on("/").trimResults().omitEmptyStrings().splitToList(filename);
      if (spath.size() > 2) {
        stitle = spath.get(1);
        if (stitle.equals("maven-plugin")) {
          stitle = "mvn jooby:run";
        } else {
          stitle += " module";
        }
      } else {
        sdesc = null;
      }
    } else {
      sdesc = null;
    }
    if (stitle != null) {
      stitle = Jsoup.parseBodyFragment(stitle).text();
    }
    if (sdesc != null) {
      sdesc = Jsoup.parseBodyFragment(sdesc).text();
    }
    String output = html.stream().collect(Collectors.joining("\n"));
    return new Page(output, toc.substring(0), raw, title,
        stitle, sdesc);
  }

  private static String id(final String text) {
    return text.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("\\-+", "-");
  }

}
