package org.jooby;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.io.FileUtils;
import org.jruby.embed.PathType;
import org.jruby.embed.ScriptingContainer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Tag;
import org.zeroturnaround.exec.ProcessExecutor;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.comments.Comment;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.github.jknack.handlebars.io.FileTemplateLoader;
import com.google.common.base.Splitter;

public class JoobySiteGenerator {

  static ScriptingContainer rubyEnv = new ScriptingContainer();

  static Object script = rubyEnv.runScriptlet(PathType.CLASSPATH, "to_html.rb");

  static boolean release = false;

  public static void main(final String[] args) throws Exception {
    Path basedir = Paths.get("..", "jooby-project");
    Path target = Paths.get("target");
    Path outDir = target.resolve("gh-pages");
    Path rootReadme = basedir.resolve("README.md");
    //    checkout(outDir);
    Path md = process(basedir.resolve("doc"));
    javadoc(basedir, outDir.resolve("apidocs"));
    Handlebars hbs = new Handlebars(
        new FileTemplateLoader(Paths.get("src", "main", "resources", "site").toFile(), ".html"));
    try (Stream<Path> walk = Files.walk(md).filter(p -> {
      String name = p.getFileName().toString();
      return (name.equals("README.md") && p.getNameCount() > 1)
          || name.equals("index.md")
          || name.equals("spec.md");
    }).sorted()) {
      Iterator<Path> it = walk.iterator();
      while (it.hasNext()) {
        Path abs = it.next();
        Path path = md.relativize(abs);
        String filename = path.toString().replace(".md", "").replace("README", "index");
        try {
          String main = readFile(abs);

          Template template = template(hbs, filename);
          Map<String, Object> data = new HashMap<>();
          String[] html = markdownToHtml(path.toString(), main);
          data.put("main", html[0]);
          data.put("toc", html[1]);
          data.put("md", html[2]);
          data.put("page-header", html[3]);
          data.put("stitle", html[4]);
          data.put("sdesc", html[5]);
          data.put("page-url", "http://jooby.org/" + path.getParent());
          data.put("year", LocalDate.now().getYear());
          data.put("infinite", "&infin;");
          data.put("version", version());
          Path output = Paths.get(outDir.resolve(path).toString()
              .replace("README.md", "index.html")
              .replace("index.md", "index.html")
              .replace("spec.md", "spec.html"));
          output.toFile().getParentFile().mkdirs();
          System.out.println("html: " + output);
          write(output, finalize(template.apply(data).trim()));

          if (release && path.toString().endsWith("README.md")) {
            Path outputgh = basedir.resolve("modules")
                .resolve(path.toString().replace("doc/", "jooby-"))
                .toAbsolutePath()
                .normalize();
            File ghdir = outputgh.toFile().getParentFile();
            if (ghdir.exists()) {
              System.out.println("releasing: " + outputgh);
              if (!outputgh.equals(rootReadme)) {
                write(outputgh, modheader(outputgh.getParent().getFileName().toString()) + main);
              } else {
                write(outputgh, main);
              }
            }
          }
          // guides
          if (path.toString().contains("guides") && !path.toString().equals("guides/index.md")) {
            // guide(target, path, main);
          }
        } catch (FileNotFoundException ex) {
          System.err.println("missing " + filename);
        }
      }
    }
    // copy main file
    Files.copy(basedir.resolve("modules").resolve("README.md"), rootReadme,
        StandardCopyOption.REPLACE_EXISTING);
    Files.deleteIfExists(basedir.resolve("modules").resolve("README.md"));
    // static files
    System.out.println("moving static resources: ");
    Path staticFiles = Paths.get("src", "main", "resources", "static-site", "resources");
    try (Stream<Path> assets = Files.walk(staticFiles)
        .filter(p -> !p.toString().endsWith(".DS_Store"))) {
      Iterator<Path> it = assets.iterator();
      while (it.hasNext()) {
        Path path = it.next();
        Path asset = outDir.resolve("resources").resolve(staticFiles.relativize(path));
        System.out.println("  " + asset);
        asset.toFile().getParentFile().mkdirs();
        if (path.toFile().isFile()) {
          copy(path, asset);
        }
      }
    }

    Guides.main(new String[]{version() });
  }

  private static String modheader(String name) {
    return
        "[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.jooby/" + name
            + "/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.jooby/" + name
            + ")\n"
            + "[![javadoc](https://javadoc.io/badge/org.jooby/" + name
            + ".svg)](https://javadoc.io/doc/org.jooby/" + name + "/" + version() + ")\n"
            + "[!["+name+" website](https://img.shields.io/badge/"+name+"-brightgreen.svg)](http://jooby.org/doc/"+name.replace("jooby-","")+")\n";
  }

  static void apidoc(final Path basedir, final Path md) throws Exception {
    Path src = basedir.resolve(Paths.get("jooby", "src", "main", "java", "org", "jooby"))
        .normalize();
    try (Stream<Path> walk = Files.walk(src).filter(p -> {
      String name = p.getFileName().toString();
      return name.endsWith(".java") && p.toString().indexOf("internal") == -1;
    }).sorted()) {
      Iterator<Path> files = walk.iterator();
      StringBuilder output = new StringBuilder();
      while (files.hasNext()) {
        Path file = files.next();
        try {
          StringBuilder javadoc = new StringBuilder();
          CompilationUnit unit = JavaParser.parse(file.toFile());
          TypeDeclaration type = unit.getTypes().get(0);
          boolean isInterface = (type instanceof ClassOrInterfaceDeclaration
              && ((ClassOrInterfaceDeclaration) type).isInterface());
          Comment comment = type.getComment();
          String h1 = "<h1>" + type.getName() + "</h1>\n";
          javadoc.append(ToMarkdown.toMd(h1, unit.toString())).append("\n\n");
          if (comment != null) {
            javadoc.append(ToMarkdown.toMd(comment.toString(), unit.toString(), 1)).append("\n\n");
          }
          javadoc.append("\n");
          List<BodyDeclaration> members = type.getMembers();
          Map<String, List<MethodDeclaration>> methods = new TreeMap<>();
          for (BodyDeclaration m : members) {
            if (m instanceof MethodDeclaration) {
              MethodDeclaration method = (MethodDeclaration) m;
              if (Modifier.isPublic(method.getModifiers()) || isInterface) {
                List<MethodDeclaration> overloaded = methods.get(method.getName());
                if (overloaded == null) {
                  overloaded = new ArrayList<>();
                  methods.put(method.getName(), overloaded);
                }
                overloaded.add(method);
              }
            }
          }
          for (Entry<String, List<MethodDeclaration>> e : methods.entrySet()) {
            String name = e.getKey();
            String m1 = "<h2>" + name + "</h2>\n";
            javadoc.append(ToMarkdown.toMd(m1, unit.toString())).append("\n");
            for (MethodDeclaration method : e.getValue()) {
              Comment mc = method.getComment();
              if (mc != null) {
                javadoc.append(ToMarkdown.toMd(mc.toString().replace("@return ", ""),
                    unit.toString(), 1)).append("\n\n");
              }
              javadoc.append(ToMarkdown.toMd("<pre>"
                      + method.getDeclarationAsString().replace("<", "&lt;").replace(">", "&gt;")
                      + "</pre>",
                  unit.toString())).append("\n\n");
            }
          }
          javadoc.append("\n");
          output.append(javadoc);
        } catch (Exception ex) {
          System.err.println("Fail to parse " + file);
          ex.printStackTrace();
        }
      }
      Path apidocs = md.resolve("apidocs").resolve("index.md");
      apidocs.toFile().getParentFile().mkdirs();
      write(apidocs, output.toString());
    }
  }

  private static void write(final Path path, final String content) throws IOException {
    if (path.toFile().exists()) {
      String left = readFile(path);
      String r = content.trim();
      if (!left.equals(r)) {
        Files.write(path, Arrays.asList(r), StandardCharsets.UTF_8);
      }
    } else {
      Files.write(path, Arrays.asList(content.trim()), StandardCharsets.UTF_8);
    }
  }

  private static void copy(final Path source, final Path dest) throws IOException {
    if (dest.toFile().isFile()) {
      byte[] b1 = Files.readAllBytes(source);
      byte[] b2 = Files.readAllBytes(dest);
      if (!Arrays.equals(b1, b2)) {
        Files.write(dest, b1);
      }
    } else {
      Files.copy(source, dest);
    }

  }

  private static String finalize(final String html) {
    Document doc = Jsoup.parse(html);
    // force external links to open in a new page:
    for (Element a : doc.select("a")) {
      String href = a.attr("href");
      if (href != null && href.length() > 0) {
        href = href.replace("https://github.com/jooby-project/jooby/tree/master/jooby-", "/doc/");
        boolean abs = href.startsWith("http://") || href.startsWith("https://");
        if (abs && !href.startsWith("http://jooby.org")) {
          a.attr("target", "_blank");
        }
        if (href.startsWith("/apidocs")) {
          a.attr("target", "_blank");
        }
        a.attr("href", href);
      }
    }

    // highlight copy bar
    doc.select("div.highlighter-rouge").prepend("<div class=\"copy-bar\">\n"
        + "<span class=\"icon-clipboard-big copy-button octicon octicon-clippy\" "
        + "title=\"copy to clipboard\"></span>"
        + "</div>");

    doc.select(".highlighter-rouge").addClass("codehilite");

    // remove br
    doc.select("br").remove();

    return doc.toString();
  }

  static void checkout(final Path outDir) throws Exception {
    cleanDir(outDir);
    System.out
        .println("git clone -b gh-pages --single-branch git@github.com:jooby-project/jooby.git .");
    File dir = outDir.toFile();
    dir.mkdirs();
    int exit = new ProcessExecutor()
        .command("git", "clone", "-b", "gh-pages", "--single-branch",
            "git@github.com:jooby-project/jooby.git", ".")
        .redirectOutput(System.err)
        .directory(dir)
        .execute()
        .getExitValue();
    if (exit != 0) {
      System.err.println("can't clone repo");
      System.exit(0);
    }
  }

  private static void javadoc(final Path basedir, final Path dir) throws Exception {
    cleanDir(dir);
    dir.toFile().mkdirs();
    int exit = new ProcessExecutor()
        .command("/usr/local/Cellar/maven/3.5.0/bin/mvn", "clean", "javadoc:javadoc", "-P",
            "gh-pages")
        .directory(basedir.toFile().getCanonicalFile())
        .redirectOutput(System.err)
        .execute()
        .getExitValue();
    if (exit != 0) {
      System.err.println("javadoc err");
      System.exit(0);
    }
    FileUtils.copyDirectory(
        basedir.resolve("target").resolve("site").resolve("apidocs").toFile().getCanonicalFile(),
        dir.toFile());
  }

  private static void cleanDir(final Path outDir) throws IOException {
    if (outDir.toFile().exists()) {
      try (Stream<Path> files = Files.walk(outDir)) {
        Iterator<Path> it = files.iterator();
        while (it.hasNext()) {
          File file = it.next().toAbsolutePath().toFile();
          if (!file.equals(outDir.toAbsolutePath().toFile())) {
            if (file.isDirectory()) {
              cleanDir(file.toPath());
              file.delete();
            } else {
              file.delete();
            }
          }
        }
      }
    }
  }

  private static Template template(final Handlebars hbs, final String filename) throws IOException {
    try {
      return hbs.compile(filename);
    } catch (FileNotFoundException ex) {
      if (filename.startsWith("doc/")) {
        return hbs.compile("doc/mod");
      }
      if (filename.startsWith("guides/")) {
        return hbs.compile("guides/guide");
      }
      if (filename.endsWith("spec.html")) {
        return hbs.compile("doc/mod");
      }
      throw ex;
    }
  }

  private static String[] markdownToHtml(final String filename, final String text) {
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
    return new String[]{html.stream().collect(Collectors.joining("\n")), toc.toString(), raw,
        title, stitle, sdesc};
  }

  private static String id(final String text) {
    return text.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("\\-+", "-");
  }

  private static Path process(final Path source) throws IOException {
    Path basedir = source.toFile().getParentFile().toPath();
    System.out.println("processing doc (*.md)");
    try (Stream<Path> walk = Files.walk(source)
        .filter(p -> p.getFileName().toString().endsWith(".md"))) {
      Path output = Paths.get("target", "md");
      cleanDir(output);
      // collect vars
      Map<String, Object> links = vars();
      Map<String, Object> vars = new LinkedHashMap<>(links);
      vars.put("toc.md", "");
      Iterator<Path> it = walk.iterator();
      List<Path> paths = new ArrayList<>();
      while (it.hasNext()) {
        Path path = it.next();
        paths.add(path);
        // content
        String main = readFile(path);

        String appendix = appendix(basedir, path);
        main = main.replace("{{appendix}}", appendix);

        if (main.startsWith("---")) {
          main = main.substring(main.indexOf("---", 1) + "---".length());
        }
        for (Entry<String, Object> var : links.entrySet()) {
          main = main.replace("{{" + var.getKey() + "}}", var.getValue().toString());
        }
        vars.put(source.relativize(path).toString(), main);
      }
      // replace content
      it = paths.iterator();
      while (it.hasNext()) {
        Path path = it.next();
        String main = readFile(path);
        if (main.startsWith("---")) {
          main = main.substring(main.indexOf("---", 1) + "---".length());
        }

        String appendix = appendix(basedir, path);
        main = main.replace("{{appendix}}", appendix);

        for (Entry<String, Object> var : vars.entrySet()) {
          main = main.replace("{{" + var.getKey() + "}}", var.getValue().toString());
        }
        String guide = path.getName(path.getNameCount() - 1).toString().replace(".md", "");
        main = main.replace("{{guide}}", guide);
        main = main.replace("{{pkgguide}}", guide.replace("-", ""));

        Path fpath = path;
        if (fpath.toFile().getParentFile().list().length == 1) {
          if (!fpath.getFileName().endsWith("index.md")
              && !fpath.getFileName().endsWith("README.md")) {
            fpath = fpath.getParent().resolve("README.md");
          }
        }
        Path md = output.resolve(source.relativize(fpath));
        Path dir = md.toFile().getParentFile().toPath();
        if (dir.endsWith("guides") && !md.endsWith("index.md")) {
          // rewrite guide
          dir = dir.resolve(md.getFileName().toString().replace(".md", ""));
          md = dir.resolve("README.md");
        }
        dir.toFile().mkdirs();
        write(md, main);
        if (fpath.getParent().getFileName().toString()
            .equals(fpath.getFileName().toString().replace(".md", ""))) {
          File rewrite = md.getParent().resolve("README.md").toFile();
          md.toFile().renameTo(rewrite);
          md = rewrite.toPath();
        }
        System.out.println("  done: " + md);
      }
      return output;
    }
  }

  private static String readFile(final Path path) {
    return readFile(path, "\n");
  }

  private static String readFile(final Path path, final String nl) {
    try {
      return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
          .collect(Collectors.joining(nl))
          .trim();
    } catch (IOException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static String appendix(final Path basedir, final Path path) throws IOException {
    try {
      File file = path.toFile();

      // find appendix
      String name = "jooby";
      if (!file.getParentFile().getName().equals("doc")) {
        name += "-" + file.getParentFile().getName();
      }
      Path rsrc = basedir.resolve(Paths.get(name, "src", "main", "resources"));
      String level = "##";
      return Files.walk(rsrc)
          .filter(p -> p.toString().endsWith(".conf") || p.toString().endsWith(".properties"))
          .map(p -> level + " " + p.getFileName().toString() + "\n\n```properties\n"
              + readFile(p, "\n\n").replaceAll("\n\n+", "\n\n")
              + "\n```\n\n")
          .collect(Collectors.joining("\n"));
    } catch (NoSuchFileException ex) {
      return "";
    }
  }

  public static Map<String, Object> vars() {
    Map<String, Object> vars = new LinkedHashMap<>();

    vars.put("year", LocalDate.now().getYear() + "");

    vars.put("metrics", "[Metrics](http://metrics.dropwizard.io)");

    vars.put("gradle", "[Gradle](http://gradle.org)");

    vars.put("netty_server", "[Netty](/doc/netty)");

    vars.put("raml", "[RAML](http://raml.org)");

    vars.put("cassandra", "[Cassandra](http://cassandra.apache.org)");

    vars.put("couchbase", "[Couchbase](http://www.couchbase.com)");

    vars.put("undertow_server", "[Undertow](/doc/undertow)");

    vars.put("site", "http://jooby.org");

    vars.put("hibernate", "[Hibernate](http://hibernate.org)");

    vars.put("twitter", "[@joobyproject](https://twitter.com/joobyproject)");

    vars.put("ggroup", "[group](https://groups.google.com/forum/#!forum/jooby-project)");

    vars.put("slack", "[gitter](https://gitter.im/jooby-project/jooby)");

    vars.put("nginx", "[nginx](https://www.nginx.com)");

    vars.put("apache", "[apache](https://httpd.apache.org)");

    vars.put("gh-prefix", "https://github.com/jooby-project/jooby/tree/master/jooby");

    vars.put("guides", "http://jooby.org/guides");

    vars.put("Jooby", "[Jooby](http://jooby.org)");

    vars.put("git", "[Git](https://git-scm.com/downloads)");

    vars.put("joobyrun",
        "[mvn jooby:run](https://github.com/jooby-project/jooby/tree/master/jooby-maven-plugin)");

    vars.put("gh-guides", "https://github.com/jooby-project");

    vars.put("java",
        "[JDK 8+](http://www.oracle.com/technetwork/java/javase/downloads/index.html)");

    vars.put("templates",
        "[starter projects](https://github.com/search?q=topic%3Astarter+org%3Ajooby-project&type=Repositories)");

    vars.put(
        "jetty_server",
        "[Jetty](/doc/jetty)");

    vars.put("h2", "[h2](http://www.h2database.com)");

    vars.put(
        "freemarker",
        "[Freemarker](http://freemarker.org)");

    vars.put(
        "gson",
        "[Gson](https://github.com/google/gson)");

    vars.put(
        "jackson",
        "[Jackson](https://github.com/FasterXML/jackson)");

    vars.put(
        "rx",
        "[RxJava](https://github.com/ReactiveX/RxJava)");

    vars.put(
        "ebean",
        "[Ebean ORM](http://ebean-orm.github.io)");

    vars.put(
        "hazelcast",
        "[Hazelcast](http://hazelcast.org)");

    vars.put(
        "less",
        "[Less](http://lesscss.org)");

    vars.put(
        "less4j",
        "[Less4j](https://github.com/SomMeri/less4j)");

    vars.put(
        "sass",
        "[Sass](http://sass-lang.com)");

    vars.put(
        "sassjava",
        "[Vaadin Sass Compiler](https://github.com/vaadin/sass-compiler)");

    vars.put(
        "flyway",
        "[Flyway](http://flywaydb.org)");

    vars.put(
        "jongo",
        "[Jongo](http://jongo.org)");

    vars.put(
        "commons-email",
        "[Apache Commons Email](https://commons.apache.org/proper/commons-email)");

    vars.put(
        "spymemcached",
        "[SpyMemcached](https://github.com/dustin/java-memcached-client)");

    vars.put(
        "memcached",
        "[Memcached](http://memcached.org)");

    vars.put(
        "swagger",
        "[Swagger](http://swagger.io)");

    vars.put(
        "pac4j",
        "[Pac4j](https://github.com/pac4j/pac4j)");

    vars.put(
        "version",
        version());

    vars.put(
        "ehcache",
        "[Ehcache](http://ehcache.org)");

    vars.put("site", "/");

    vars.put("apidocs", "/apidocs");

    vars.put("defdocs", "/apidocs/org/jooby");

    vars.put("request", "[request](/apidocs/org/jooby/Request.html)");
    vars.put("response", "[response](/apidocs/org/jooby/Response.html)");
    vars.put("rsp_send", "[rsp.send()](/apidocs/org/jooby/Response.html#send-java.lang.Object-)");
    vars.put("chain_next",
        "[chain.next(req, rsp)](/apidocs/org/jooby/Route.Chain.html#next-org.jooby.Request-org.jooby.Response-)");
    vars.put("result", "[result](/apidocs/org/jooby/Result.html)");
    vars.put("file_upload", "[file upload](/apidocs/org/jooby/Upload.html)");
    vars.put("formurlencoded", "`application/x-www-form-urlencoded`");
    vars.put("formmultipart", "`multipart/form-data`");
    vars.put("route_map", "[map](/apidocs/org/jooby/Jooby.html#map-org.jooby.Route.Mapper-)");
    vars.put("route_with", "[with](/apidocs/org/jooby/Jooby.html#with-java.lang.Runnable-)");
    vars.put("route_excludes",
        "[excludes](/apidocs/org/jooby/Route.Definition.html#excludes-java.util.List-)");
    vars.put("route_consumes",
        "[consumes](/apidocs/org/jooby/Route.Definition.html#consumes-java.util.List-)");
    vars.put("route_produces",
        "[produces](/apidocs/org/jooby/Route.Definition.html#produces-java.util.List-)");
    vars.put("req_param",
        "[req.param(\"name\")](/apidocs/org/jooby/Request.html#param-java.lang.String-)");
    vars.put("req_header",
        "[req.header(\"name\")](/apidocs/org/jooby/Request.html#header-java.lang.String-)");
    vars.put("mutant", "[mutant](/apidocs/org/jooby/Mutant.html)");
    vars.put("parser", "[parser](/apidocs/org/jooby/Parser.html)");

    vars.put("req_filter", "[filter](/apidocs/org/jooby/Route.Filter.html)");
    vars.put("req_handler", "[handler](/apidocs/org/jooby/Route.Handler.html)");
    vars.put("req_bodyc",
        "[req.body(Class)](/apidocs/org/jooby/Request.html#body-java.lang.Class-)");
    vars.put("req_body", "[req.body()](/apidocs/org/jooby/Request.html#body--)");
    vars.put("deferred", "[deferred](/apidocs/org/jooby/Deferred.html)");

    vars.put(
        "maven",
        "[Maven 3+](http://maven.apache.org/)");

    vars.put(
        "guice",
        "[Guice](https://github.com/google/guice)");

    vars.put(
        "jooby",
        "[Jooby](http://jooby.org)");

    vars.put(
        "netty",
        "[Netty](http://netty.io)");

    vars.put(
        "jetty",
        "[Jetty](http://www.eclipse.org/jetty/)");

    vars.put(
        "undertow",
        "[Undertow](http://undertow.io)");

    vars.put(
        "npm",
        "[npm](https://www.npmjs.com)");

    vars.put(
        "grunt",
        "[npm](http://gruntjs.com)");

    vars.put(
        "redis",
        "[Redis](http://redis.io)");

    vars.put(
        "jedis",
        "[Jedis](https://github.com/xetorthio/jedis)");

    vars.put(
        "expressjs",
        "[express.js](http://expressjs.com)");

    vars.put(
        "sinatra",
        "[Sinatra](http://www.sinatrarb.com)");

    vars.put(
        "spring",
        "[Spring](http://spring.io)");

    vars.put(
        "jersey",
        "[Jersey](https://jersey.java.net)");

    vars.put(
        "hikari",
        "[Hikari](https://github.com/brettwooldridge/HikariCP)");

    vars.put(
        "mongodb",
        "[MongoDB](http://mongodb.github.io/mongo-java-driver/)");

    vars.put(
        "mongodbapi",
        "http://api.mongodb.org/java/2.13/com/mongodb");

    vars.put(
        "gh",
        "https://github.com/jooby-project/jooby/tree/master");

    vars.put(
        "morphia",
        "[Morphia](https://github.com/mongodb/morphia)");

    vars.put(
        "morphiaapi",
        "https://rawgit.com/wiki/mongodb/morphia/javadoc/0.111/org/mongodb/morphia");

    vars.put(
        "jboss-modules",
        "[JBoss Modules](https://github.com/jboss-modules/jboss-modules)");

    vars.put(
        "elasticsearch",
        "[Elastic Search](https://github.com/elastic/elasticsearch)");

    return vars;
  }

  private static String version() {
    return "1.2.0";
  }

}
