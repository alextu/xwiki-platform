/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.rendering.internal.parser.wikimodel.xhtml;

import java.util.Stack;

import org.wikimodel.wem.WikiParameter;
import org.wikimodel.wem.WikiParameters;
import org.wikimodel.wem.WikiReference;
import org.wikimodel.wem.xhtml.handler.CommentHandler;
import org.wikimodel.wem.xhtml.impl.XhtmlHandler.TagStack;
import org.xwiki.rendering.internal.parser.wikimodel.XWikiGeneratorListener;
import org.xwiki.rendering.listener.Image;
import org.xwiki.rendering.listener.Listener;
import org.xwiki.rendering.parser.ImageParser;
import org.xwiki.rendering.parser.LinkParser;
import org.xwiki.rendering.parser.StreamParser;
import org.xwiki.rendering.renderer.PrintRenderer;
import org.xwiki.rendering.renderer.PrintRendererFactory;
import org.xwiki.rendering.renderer.printer.DefaultWikiPrinter;
import org.xwiki.xml.XMLUtils;

/**
 * Handle Link and Macro definitions in comments (we store links in a comment since otherwise there are situations where
 * it's not possible to reconstruct the original reference from the rendered HTML value and for macros it wouldn't be
 * possible at all to reconstruct the macro).
 * 
 * @version $Id$
 * @since 1.7M1
 */
public class XWikiCommentHandler extends CommentHandler
{
    private StreamParser parser;

    private LinkParser linkParser;

    private ImageParser imageParser;

    private PrintRendererFactory xwikiSyntaxPrintRendererFactory;

    private PrintRendererFactory plainRendererFactory;

    /**
     * We're using a stack so that we can have nested comment handling. For example when we have a link to an image we
     * need nested comment support.
     */
    private Stack<String> commentContentStack = new Stack<String>();

    /**
     * @since 2.1RC1
     * @todo Remove the need to pass a Parser when WikiModel implements support for wiki syntax in links. See
     *       http://code.google.com/p/wikimodel/issues/detail?id=87
     */
    public XWikiCommentHandler(StreamParser parser, LinkParser linkParser, ImageParser imageParser,
        PrintRendererFactory xwikiSyntaxPrintRendererFactory, PrintRendererFactory plainRendererFactory)
    {
        this.parser = parser;
        this.linkParser = linkParser;
        this.xwikiSyntaxPrintRendererFactory = xwikiSyntaxPrintRendererFactory;
        this.imageParser = imageParser;
        this.plainRendererFactory = plainRendererFactory;
    }

    @Override
    public void onComment(String content, TagStack stack)
    {
        // if ignoreElements is true it means we are inside a macro or another block we don't want to parse content
        boolean ignoreElements = (Boolean) stack.getStackParameter("ignoreElements");

        // If the comment starts with "startwikilink" then we need to gather all XHTML tags inside
        // the A tag, till we get a "stopwikilink" comment.
        // Same for "startimage" and "stopimage".
        if (!ignoreElements && content.startsWith("startwikilink:")) {
            handleLinkCommentStart(XMLUtils.unescapeXMLComment(content), stack);
        } else if (!ignoreElements && content.startsWith("stopwikilink")) {
            handleLinkCommentStop(XMLUtils.unescapeXMLComment(content), stack);
        } else if (!ignoreElements && content.startsWith("startimage:")) {
            handleImageCommentStart(XMLUtils.unescapeXMLComment(content), stack);
        } else if (!ignoreElements && content.startsWith("stopimage")) {
            handleImageCommentStop(XMLUtils.unescapeXMLComment(content), stack);
        } else if (!ignoreElements && content.startsWith("startmacro")) {
            super.onComment(XMLUtils.unescapeXMLComment(content), stack);
        } else {
            super.onComment(content, stack);
        }
    }

    private void handleLinkCommentStart(String content, TagStack stack)
    {
        // Since wikimodel does not support wiki syntax in link labels we need to pass the link label "as is" (as it
        // originally appears in the parsed source) and handle it specially in the
        // XDOMGeneratorListener.createLinkBlock(), with the parser passed as the first parameter in the
        // XDOMGeneratorListener constructor.
        // Since we cannot get this label as it originally appeared in the HTML source ( we are doing a SAX-like
        // parsing), we should render the XDOM as HTML to get an HTML label.
        // Since any syntax would do it, as long as this renderer matches the corresponding XDOMGeneratorListener
        // parser, we use an xwiki 2.0 renderer for it is less complex (no context needed to render xwiki 2.0, no url
        // resolution needed, no reference validity tests).
        // see XDOMGeneratorListener#XDOMGeneratorListener(Parser, LinkParser, ImageParser)
        // see WikiModelXHTMLParser#getLinkLabelParser()
        // see http://code.google.com/p/wikimodel/issues/detail?id=87
        // TODO: remove this workaround when wiki syntax in link labels will be supported by wikimodel
        DefaultWikiPrinter printer = new DefaultWikiPrinter();

        PrintRenderer linkLabelRenderer = this.xwikiSyntaxPrintRendererFactory.createRenderer(printer);
        // Make sure to flush whatever the renderer implementation
        linkLabelRenderer.beginDocument(Listener.EMPTY_PARAMETERS);

        XWikiGeneratorListener xwikiListener =
                new XWikiGeneratorListener(this.parser, linkLabelRenderer, this.linkParser, this.imageParser,
                    this.plainRendererFactory, null);

        stack.pushStackParameter("linkListener", xwikiListener);

        stack.pushStackParameter("isInLink", true);
        stack.pushStackParameter("isFreeStandingLink", false);
        stack.pushStackParameter("linkParameters", WikiParameters.EMPTY);

        this.commentContentStack.push(content.substring("startwikilink:".length()));
    }

    private void handleLinkCommentStop(String content, TagStack stack)
    {
        XWikiGeneratorListener xwikiListener = (XWikiGeneratorListener) stack.popStackParameter("linkListener");
        PrintRenderer linkLabelRenderer = (PrintRenderer) xwikiListener.getListener();

        // Make sure to flush whatever the renderer implementation
        linkLabelRenderer.endDocument(Listener.EMPTY_PARAMETERS);

        boolean isFreeStandingLink = (Boolean) stack.getStackParameter("isFreeStandingLink");
        String linkComment = this.commentContentStack.pop();
        if (isFreeStandingLink) {
            stack.getScannerContext().onReference(linkComment);
        } else {
            String label = linkLabelRenderer.getPrinter().toString();
            String reference = linkComment;
            WikiParameters params = (WikiParameters) stack.getStackParameter("linkParameters");

            WikiReference wikiReference = new WikiReference(reference, label, params);

            stack.getScannerContext().onReference(wikiReference);
        }

        stack.popStackParameter("isInLink");
        stack.popStackParameter("isFreeStandingLink");
        stack.popStackParameter("linkParameters");
    }

    private void handleImageCommentStart(String content, TagStack stack)
    {
        stack.setStackParameter("isInImage", true);
        this.commentContentStack.push(content.substring("startimage:".length()));
    }

    private void handleImageCommentStop(String content, TagStack stack)
    {
        boolean isFreeStandingImage = (Boolean) stack.getStackParameter("isFreeStandingImage");
        WikiParameters parameters = (WikiParameters) stack.getStackParameter("imageParameters");
        String imageComment = this.commentContentStack.pop();
        Image image = this.imageParser.parse(imageComment);

        if (isFreeStandingImage) {

            stack.getScannerContext().onImage(imageComment);
        } else {
            // Remove the ALT attribute if the content has the same value as the original image location
            // This is because the XHTML renderer automatically adds an ALT attribute since it is mandatory
            // in the XHTML specifications.
            WikiParameter alt = parameters.getParameter("alt");
            if (alt != null && alt.getValue().equals(image.getName())) {
                parameters = parameters.remove("alt");
            }

            WikiReference reference = new WikiReference(imageComment, null, parameters);

            stack.getScannerContext().onImage(reference);
        }

        stack.setStackParameter("isInImage", false);
        stack.setStackParameter("isFreeStandingImage", false);
        stack.setStackParameter("imageParameters", WikiParameters.EMPTY);
    }
}
