package org.jooby;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;

import com.github.jknack.handlebars.Context;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.github.jknack.handlebars.context.FieldValueResolver;
import com.github.jknack.handlebars.io.ClassPathTemplateLoader;
import com.github.jknack.handlebars.io.CompositeTemplateLoader;
import com.github.jknack.handlebars.io.FileTemplateLoader;

public class Guides {

  private static final Pattern MD_LINK = Pattern.compile("\\[(.*)\\]\\((.*)\\)");

  private String version;

  private Path source;

  private String maven;

  private boolean clean;

  private Handlebars hbs;

  private Path mdsrc;

  private MarkdownToHtml md;

  private Path output;

  private boolean verify = true;

  private String gradle;

  public Guides(final String version, final Path basedir, final Path mdsrc) {
    this.version = version;
    this.source = basedir.resolve("guides");
    this.output = basedir.resolve("gh-pages").resolve("guides");
    this.mdsrc = mdsrc;
    this.md = new MarkdownToHtml();
    this.hbs = new Handlebars(
        new CompositeTemplateLoader(new ClassPathTemplateLoader("/site", ".html"),
            new FileTemplateLoader(mdsrc.getParent().toFile(), ".md")));
    hbs.stringParams(true);

    hbs.registerHelper("link", (value, opts) -> {
      Matcher matcher = MD_LINK.matcher(value.toString());
      String output = value.toString();
      String label;
      if (matcher.matches()) {
        output = matcher.group(2);
        label = matcher.group(1);
      } else {
        label = opts.param(0, value.toString());
      }
      boolean website = opts.get("website", false);
      if (website) {
        output = output.replace("https://github.com/jooby-project/jooby/tree/master/jooby-",
            "/doc/");
      } else if (!output.startsWith("http")) {
        output = "http://jooby.org" + output;
      }
      return new Handlebars.SafeString("[" + label + "](" + output + ")");
    });

    hbs.registerHelper("modlink", (value, opts) -> {
      boolean website = opts.get("website", false);
      String link;
      if (website) {
        link = "/doc/" + value;
      } else {
        link = "https://github.com/jooby-project/jooby/tree/master/jooby-" + value;
      }
      return new Handlebars.SafeString("[" + value + "](" + link + ")");
    });

    hbs.registerHelper("source", (path, opts) -> {
      String guide = opts.get("guide");
      Path src = Paths.get(path.toString());
      if (!Files.exists(src)) {
        src = source.resolve(guide).resolve("src").resolve("main").resolve("java")
            .resolve(guide.replace("-guide", ""))
            .resolve(path.toString());
      }
      return new Handlebars.SafeString(
          Files.readAllLines(src).stream().collect(Collectors.joining("\n")));
    });

    hbs.registerHelper("javadoc", (value, options) -> {
      StringBuilder buff = new StringBuilder();
      String method = options.param(0, null);
      StringBuilder deflabel = new StringBuilder().append(value.toString());
      buff.append("(http://jooby.org/apidocs/org/jooby/").append(value)
          .append(".html");
      if (method != null) {
        deflabel
            .append(".").append(method).append("(");
        buff.append("#").append(method).append("-");
        String sep = ", ";
        for (int i = 1; i < options.params.length; i++) {
          String type = options.param(i).toString();
          String[] segments = type.split("\\.");
          buff.append(type);
          buff.append("-");
          deflabel.append(segments[segments.length - 1]).append(sep);
        }
        if (options.params.length == 1) {
          buff.append("-");
        } else {
          deflabel.setLength(deflabel.length() - sep.length());
        }
        deflabel.append(")");
      }
      String label = options.hash("label", deflabel.toString());
      return "[" + label + "]" + buff.append(")");
    });
  }

  public void sync(final String... guides) throws Exception {
    for (String guide : guides) {
      guide(guide);
    }
  }

  private Guides maven(final String maven) {
    this.maven = maven;
    return this;
  }

  private Guides gradle(final String gradle) {
    this.gradle = gradle;
    return this;
  }

  private Guides clean(final boolean b) {
    this.clean = b;
    return this;
  }

  private void guide(final String name) throws Exception {
    System.out.printf("processing %s\n", name);

    Path guideSrc = source.resolve(name);
    if (clean) {
      FS.rm(guideSrc);
    }
    FS.mkdirs(guideSrc);

    boolean build = true;
    if (!Files.exists(guideSrc.resolve(".git"))) {
      try {
        new Git("jooby-project", name, guideSrc).clone();
      } catch (Exception x) {
        build = false;
      }
    }

    if (build) {
      verifyMainClass(guideSrc);

      upgrade(guideSrc, version);

      if (verify) {
        if(Files.exists(guideSrc.resolve("pom.xml"))) {
          new Build(guideSrc).executable(maven).run("clean", "package");
        } else {
          new Build(guideSrc).executable(gradle).run("build");
        }
        new Git("jooby-project", name, guideSrc).commit("v" + version);
      }
    }

    //    Path mdinput = mdsrc.resolve(name.replace("-guide", "") + ".md");
    //    if (Files.exists(mdinput)) {
    //      Path mdoutput = guideSrc.resolve("README.md");
    //      toMarkdown(name, mainClass, mdinput, mdoutput, false);
    //      toHtml(name, mainClass, mdinput, toMarkdown(name, mainClass, mdinput, mdoutput, true));
    //    }
  }

  private String toMarkdown(final String name, final Path mainclass, final Path input,
      final Path output, final boolean website) throws IOException {
    Map<String, Object> vars = JoobySiteGenerator.vars();
    vars.put("guide", name);
    vars.put("pkgguide", "org.jooby.guides");
    vars.put("version", version);
    vars.put("website", website);
    vars.put("mainclass", mainclass.toString());

    System.out.println("toMarkdown: " + input);
    String markdown = Files.readAllLines(input).stream()
        .collect(Collectors.joining("\n"));

    String content = hbs.compileInline(markdown).apply(vars);
    if (!website) {
      Files.write(output, Arrays.asList(content));
      System.out.println("done " + output);
    }
    return content.trim();
  }

  private void toHtml(final String name, final Path mainclass, final Path input,
      final String markdown) throws IOException {

    System.out.println("toHtml: " + input);

    Page page = md.toHtml(input.toString(), markdown);
    Template template = hbs.compile("guides/guide");

    Context ctx = Context.newBuilder(page)
        .combine("guide", name)
        .push(FieldValueResolver.INSTANCE)
        .build();
    String html = template.apply(ctx).trim();
    Path outdir = output.resolve(name.replace("-guide", ""));
    FS.rm(outdir);
    FS.mkdirs(outdir);
    Path fout = outdir.resolve("index.html");
    Files.write(fout, Arrays.asList(html));
    System.out.println("done " + fout);
  }

  private void verifyMainClass(final Path dir) throws IOException {
    Path file = dir.resolve("pom.xml");
    if (Files.exists(file)) {
      Document xml = parseXml(file);

      AtomicReference<Path> appclass = new AtomicReference<>();
      xml.select("properties").first().children().forEach(it -> {
        if (it.tagName().equals("application.class")) {
          Path src = dir.resolve("src").resolve("main").resolve("java");
          Path mainclass = Arrays.asList(it.text().split("\\.")).stream()
              .reduce(src, Path::resolve, (l, r) -> l.resolve(r));
          if (mainclass.toString().endsWith("Kt")) {
            appclass.set(
                Paths.get(mainclass.toString().replace("java", "kotlin").replace("Kt", ".kt")));
          } else {
            appclass.set(Paths.get(mainclass.toString() + ".java"));
          }
        }
      });

      Path path = appclass.get();
      if (path == null || !Files.exists(path)) {
        throw new IllegalStateException("<application.class> missing or invalid: " + path);
      }
    }
  }

  private void upgrade(final Path dir, final String version) throws IOException {
    Path file = dir.resolve("pom.xml");
    if (Files.exists(file)) {
      System.out.printf("Updating %s to %s\n", file, version);

      Document doc = parseXml(file);

      doc.select("parent version").first().text(version);

      doc.select("properties").first().children().forEach(it -> {
        if (it.tagName().equals("jooby.version")) {
          it.text(version);
        }
      });

      Files.write(file, doc.toString().getBytes(StandardCharsets.UTF_8));
    } else {
      file = dir.resolve("build.gradle");
      String gradle = Files.readAllLines(file).stream()
          .collect(Collectors.joining("\n"));
      gradle = gradle.replaceAll("joobyVersion = \".*\"", "joobyVersion = \"" + version + "\"");

      Files.write(file, gradle.getBytes(StandardCharsets.UTF_8));
    }
  }

  private Document parseXml(final Path file) throws IOException {
    String content = Files.readAllLines(file).stream().collect(Collectors.joining("\n"));
    Document doc = Jsoup.parse(content, "http://maven.apache.org/POM/4.0.0", Parser.xmlParser())
        .outputSettings(new Document.OutputSettings().prettyPrint(false));
    return doc;
  }

  public static void main(final String[] args) throws Exception {
    new Guides(args.length > 0 ? args[0] : JoobySiteGenerator.version(), Paths.get("target"),
        Paths.get("../jooby-project/doc/guides"))
        .clean(false)
        .verify(true)
        .maven("/usr/local/Cellar/maven/3.5.2/libexec/bin/mvn")
        .gradle("/usr/local/Cellar/gradle/4.8/bin/gradle")
        .sync(
            "kotlin-gradle-starter",
            "apitool-starter",
            "kotlin-starter",
            "greeting",
            "livereload-starter",
            "rocker-starter",
            "jdbi-starter",
            "requery-starter",
            "gradle-starter",
            "ebean-starter",
            "apitool-kotlin-starter",
            "websocket-starter",
            "pac4j-starter",
            "hello-starter",
            "pebble-starter",
            "webpack-starter",
            "jdbi-guide");
  }

  private Guides verify(final boolean verify) {
    this.verify = verify;
    return this;
  }

}
