/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sejda.sambox.output;

import static java.util.Optional.ofNullable;
import static org.sejda.util.RequireUtils.requireNotNullArg;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import org.sejda.io.CountingWritableByteChannel;
import org.sejda.sambox.encryption.EncryptionContext;
import org.sejda.sambox.input.IncrementablePDDocument;
import org.sejda.sambox.pdmodel.PDDocument;
import org.sejda.sambox.util.SpecVersionUtils;
import org.sejda.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writer for a {@link IncrementablePDDocument}. This component provides methods to write a
 * {@link IncrementablePDDocument} to a {@link CountingWritableByteChannel} performing an incremental update.
 * 
 * @author Andrea Vacondio
 */
public class IncrementablePDDocumentWriter implements Closeable
{
    private static final Logger LOG = LoggerFactory.getLogger(IncrementablePDDocumentWriter.class);
    private DefaultPDFWriter writer;
    private PDFWriteContext context;
    private Optional<EncryptionContext> encryptionContext;
    private CountingWritableByteChannel channel;
    private WriteOption[] options;

    public IncrementablePDDocumentWriter(CountingWritableByteChannel channel,
            WriteOption... options)
    {
        requireNotNullArg(channel, "Cannot write to a null channel");
        this.channel = channel;
        this.options = options;
        this.encryptionContext = ofNullable(encryptionContext).orElseGet(Optional::empty);

    }

    /**
     * Writes the {@link PDDocument}.
     * 
     * @param document
     * @param standardSecurity
     * @throws IOException
     */
    public void write(IncrementablePDDocument document) throws IOException
    {
        requireNotNullArg(document, "PDDocument cannot be null");
        this.context = new PDFWriteContext(document.highestExistingReference().objectNumber(),
                this.encryptionContext.map(EncryptionContext::encryptionAlgorithm).orElse(null),
                options);
        this.writer = new DefaultPDFWriter(new IndirectObjectsWriter(channel, context));

        if (context.hasWriteOption(WriteOption.XREF_STREAM)
                || context.hasWriteOption(WriteOption.OBJECT_STREAMS))
        {
            document.requireMinVersion(SpecVersionUtils.V1_5);
        }
        // TODO encryption
        /**
         * ofNullable(document.getDocument().getTrailer()).map(t -> t.getCOSObject()) .ifPresent(t ->
         * t.removeItem(COSName.ENCRYPT));
         * 
         * encryptionContext.ifPresent(c -> { document.getDocument()
         * .setEncryptionDictionary(c.security.encryption.generateEncryptionDictionary(c)); LOG.debug(
         * "Generated encryption dictionary"); ofNullable(document.getDocumentCatalog().getMetadata()).map(m ->
         * m.getCOSObject()) .ifPresent(str -> str.encryptable(c.security.encryptMetadata)); });
         **/
        // TODO what to do if doc was corrupted and we did a fullscan
        try (InputStream stream = document.incrementedAsStream())
        {
            writer.writer().write(stream);
        }
        writer.writer().writeEOL();
        writeBody(document);
        writeXref(document);
    }

    private void writeBody(IncrementablePDDocument document) throws IOException
    {
        try (AbstractPDFBodyWriter bodyWriter = objectStreamWriter(
                new IncrementalPDFBodyWriter(writer.writer(), context)))
        {
            LOG.debug("Writing body using " + bodyWriter.getClass());
            bodyWriter.write(document);
        }
    }

    private AbstractPDFBodyWriter objectStreamWriter(AbstractPDFBodyWriter wrapped)
    {
        if (context.hasWriteOption(WriteOption.OBJECT_STREAMS))
        {
            return new ObjectsStreamPDFBodyWriter(wrapped);
        }
        return wrapped;
    }

    private void writeXref(IncrementablePDDocument document) throws IOException
    {
        // TODO if pref xref is -1 due to full scan
        if (context.hasWriteOption(WriteOption.XREF_STREAM)
                || context.hasWriteOption(WriteOption.OBJECT_STREAMS))
        {
            writer.writeXrefStream(document.trailer().getCOSObject(),
                    document.trailer().xrefOffset());
        }
        else
        {
            long startxref = writer.writeIncrementalXrefTable();
            writer.writeTrailer(document.trailer().getCOSObject(), startxref,
                    document.trailer().xrefOffset());
        }
    }

    @Override
    public void close() throws IOException
    {
        IOUtils.close(writer);
    }
}