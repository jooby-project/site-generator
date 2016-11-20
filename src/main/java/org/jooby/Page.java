package org.jooby;

public class Page {

  public final String content;

  public final String toc;

  public final String raw;

  public final String header;

  public final String title;

  public final String desc;

  public Page(final String content, final String toc, final String raw, final String header,
      final String title, final String desc) {
    this.content = content;
    this.toc = toc;
    this.raw = raw;
    this.header = header;
    this.title = title;
    this.desc = desc;
  }

}
