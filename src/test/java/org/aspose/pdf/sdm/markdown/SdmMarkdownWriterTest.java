package org.aspose.pdf.sdm.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.aspose.pdf.sdm.CodeBlock;
import org.aspose.pdf.sdm.Heading;
import org.aspose.pdf.sdm.LinkInline;
import org.aspose.pdf.sdm.ListBlock;
import org.aspose.pdf.sdm.ListItem;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.Quote;
import org.aspose.pdf.sdm.Run;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.Table;
import org.aspose.pdf.sdm.TableCell;
import org.aspose.pdf.sdm.TableRow;
import org.aspose.pdf.sdm.TextStyle;
import org.aspose.pdf.sdm.ThematicBreak;
import org.junit.jupiter.api.Test;

/**
 * Unit gate for {@link SdmMarkdownWriter}: each SDM block type must serialize to
 * its canonical CommonMark / GFM form. Pure in-memory — no disk access.
 */
public class SdmMarkdownWriterTest {

    private static String write(SdmDocument doc) throws IOException {
        return new SdmMarkdownWriter().write(doc);
    }

    private static Paragraph para(String text) {
        Paragraph p = new Paragraph();
        p.getInline().add(new Run(text, null));
        return p;
    }

    @Test
    public void headingsUseHashes() throws IOException {
        SdmDocument doc = new SdmDocument();
        Heading h = new Heading(2);
        h.getInline().add(new Run("Title", null));
        doc.getChildren().add(h);
        assertTrue(write(doc).contains("## Title"), write(doc));
    }

    @Test
    public void boldAndItalicRuns() throws IOException {
        SdmDocument doc = new SdmDocument();
        Paragraph p = new Paragraph();
        TextStyle b = new TextStyle();
        b.setBold(true);
        TextStyle i = new TextStyle();
        i.setItalic(true);
        p.getInline().add(new Run("bold", b));
        p.getInline().add(new Run(" and ", null));
        p.getInline().add(new Run("italic", i));
        doc.getChildren().add(p);
        String md = write(doc);
        assertTrue(md.contains("**bold**"), md);
        assertTrue(md.contains("*italic*"), md);
    }

    @Test
    public void unorderedAndOrderedLists() throws IOException {
        SdmDocument doc = new SdmDocument();
        ListBlock ul = new ListBlock(false, null);
        ListItem a = new ListItem();
        a.getChildren().add(para("apple"));
        ListItem b = new ListItem();
        b.getChildren().add(para("banana"));
        ul.getItems().add(a);
        ul.getItems().add(b);
        doc.getChildren().add(ul);

        ListBlock ol = new ListBlock(true, 1);
        ListItem one = new ListItem();
        one.getChildren().add(para("first"));
        ol.getItems().add(one);
        doc.getChildren().add(ol);

        String md = write(doc);
        assertTrue(md.contains("- apple"), md);
        assertTrue(md.contains("- banana"), md);
        assertTrue(md.contains("1. first"), md);
    }

    @Test
    public void gfmPipeTable() throws IOException {
        SdmDocument doc = new SdmDocument();
        Table t = new Table();
        TableRow header = new TableRow(TableRow.Kind.HEADER);
        header.getCells().add(cell("Name", TableCell.Kind.TH));
        header.getCells().add(cell("Age", TableCell.Kind.TH));
        TableRow body = new TableRow(TableRow.Kind.BODY);
        body.getCells().add(cell("Alice", TableCell.Kind.TD));
        body.getCells().add(cell("30", TableCell.Kind.TD));
        t.getRows().add(header);
        t.getRows().add(body);
        doc.getChildren().add(t);
        String md = write(doc);
        assertTrue(md.contains("| Name | Age |"), md);
        assertTrue(md.contains("| --- | --- |"), md);
        assertTrue(md.contains("| Alice | 30 |"), md);
    }

    private static TableCell cell(String text, TableCell.Kind kind) {
        TableCell c = new TableCell();
        c.setKind(kind);
        c.getChildren().add(para(text));
        return c;
    }

    @Test
    public void fencedCodeBlockKeepsLanguage() throws IOException {
        SdmDocument doc = new SdmDocument();
        doc.getChildren().add(new CodeBlock("print(1)", "python"));
        String md = write(doc);
        assertTrue(md.contains("```python"), md);
        assertTrue(md.contains("print(1)"), md);
    }

    @Test
    public void linkAndQuoteAndRule() throws IOException {
        SdmDocument doc = new SdmDocument();
        Paragraph p = new Paragraph();
        LinkInline link = new LinkInline("https://example.com");
        link.getChildren().add(new Run("Example", null));
        p.getInline().add(link);
        doc.getChildren().add(p);

        Quote q = new Quote();
        q.getChildren().add(para("quoted text"));
        doc.getChildren().add(q);

        doc.getChildren().add(new ThematicBreak());

        String md = write(doc);
        assertTrue(md.contains("[Example](https://example.com)"), md);
        assertTrue(md.contains("> quoted text"), md);
        assertTrue(md.contains("\n---\n") || md.endsWith("---\n"), md);
    }

    @Test
    public void frontMatterFromMetadata() throws IOException {
        SdmDocument doc = new SdmDocument();
        doc.getMetadata().setTitle("My Doc");
        doc.getMetadata().setAuthor("Jane");
        doc.getChildren().add(para("body"));
        String md = write(doc);
        assertTrue(md.startsWith("---\n"), md);
        assertTrue(md.contains("title: My Doc"), md);
        assertTrue(md.contains("author: Jane"), md);
    }

    @Test
    public void specialCharsAreEscaped() throws IOException {
        SdmDocument doc = new SdmDocument();
        doc.getChildren().add(para("a*b_c[d]"));
        String md = write(doc);
        assertEquals(true, md.contains("a\\*b\\_c\\[d\\]"), md);
    }
}
