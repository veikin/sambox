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

import static org.sejda.sambox.output.DefaultCOSWriter.SPACE;
import static org.sejda.sambox.util.SpecVersionUtils.PDF_HEADER;
import static org.sejda.util.RequireUtils.requireNotNullArg;

import java.io.Closeable;
import java.io.IOException;
import java.util.Optional;
import java.util.TreeMap;

import org.sejda.io.BufferedCountingChannelWriter;
import org.sejda.sambox.cos.COSDictionary;
import org.sejda.sambox.cos.COSDocument;
import org.sejda.sambox.cos.COSName;
import org.sejda.sambox.cos.IndirectCOSObjectReference;
import org.sejda.sambox.util.Charsets;
import org.sejda.sambox.xref.XrefEntry;
import org.sejda.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Component capable of writing parts of a PDF document (header, xref, body...) using the given
 * {@link BufferedCountingChannelWriter}.
 * 
 * @author Andrea Vacondio
 */
class PDFWriter implements Closeable
{
    private static final Logger LOG = LoggerFactory.getLogger(PDFWriter.class);

    private static final byte COMMENT = '%';
    private static final byte[] GARBAGE = new byte[] { (byte) 0xA7, (byte) 0xE3, (byte) 0xF1,
            (byte) 0xF1 };
    private static final byte[] OBJ = "obj".getBytes(Charsets.US_ASCII);
    private static final byte[] ENDOBJ = "endobj".getBytes(Charsets.US_ASCII);

    private TreeMap<Long, XrefEntry> written = new TreeMap<>();
    private COSWriter writer;

    public PDFWriter(COSWriter writer)
    {
        requireNotNullArg(writer, "Cannot write to a null COSWriter");
        this.writer = writer;
    }

    void writeHeader(String version) throws IOException
    {
        LOG.debug("Writing header " + version);
        writer().write(PDF_HEADER);
        writer().write(version);
        writer().writeEOL();
        writer().write(COMMENT);
        writer().write(GARBAGE);
        writer().writeEOL();
    }

    /**
     * Writes the given {@link IndirectCOSObjectReference} updating its offset and releasing it once written. Object
     * numbers are cached to avoid writing the same object multiple times.
     * 
     * @param object
     * @throws IOException
     */
    void writerObject(IndirectCOSObjectReference object) throws IOException
    {
        if (!written.containsKey(object.xrefEntry().getObjectNumber()))
        {
            doWriteObject(object);
            written.put(object.xrefEntry().getObjectNumber(), object.xrefEntry());
            object.releaseCOSObject();
            LOG.trace("Released " + object);
        }
    }

    private void doWriteObject(IndirectCOSObjectReference object) throws IOException
    {
        object.xrefEntry().setByteOffset(writer().offset());
        writer().write(Long.toString(object.xrefEntry().getObjectNumber()));
        writer().write(SPACE);
        writer().write(Integer.toString(object.xrefEntry().getGenerationNumber()));
        writer().write(SPACE);
        writer().write(OBJ);
        writer().writeEOL();
        object.getCOSObject().accept(writer);
        writer().writeEOL();
        writer().write(ENDOBJ);
        writer().writeEOL();
        LOG.trace("Written object " + object.xrefEntry());
    }

    void writeBody(COSDocument document, AbstractPdfBodyWriter bodyWriter) throws IOException
    {
        LOG.debug("Writing body using " + bodyWriter.getClass());
        try
        {
            bodyWriter.write(document);
        }
        finally
        {
            IOUtils.close(bodyWriter);
        }
    }

    /**
     * writes the xref table
     * 
     * @return the startxref value
     * @throws IOException
     */
    long writeXrefTable() throws IOException
    {
        long startxref = writer().offset();
        LOG.debug("Writing xref table at offset " + startxref);
        if (written.put(0L, XrefEntry.DEFAULT_FREE_ENTRY) != null)
        {
            LOG.warn("Reserved object number 0 has been overwritten with the expected free entry");
        }
        writer().write("xref");
        writer().writeEOL();
        writer().write("0 " + written.size());
        writer().writeEOL();
        for (long key = 0; key <= written.lastKey(); key++)
        {
            writer().write(
                    Optional.ofNullable(written.get(key)).orElse(XrefEntry.DEFAULT_FREE_ENTRY)
                            .toXrefTableEntry());
        }
        return startxref;
    }

    void writeTrailer(COSDictionary trailer, long startxref) throws IOException
    {
        LOG.trace("Writing trailer");
        trailer.removeItem(COSName.PREV);
        trailer.removeItem(COSName.XREF_STM);
        trailer.removeItem(COSName.DOC_CHECKSUM);
        trailer.removeItem(COSName.DECODE_PARMS);
        trailer.removeItem(COSName.F_DECODE_PARMS);
        trailer.removeItem(COSName.F_FILTER);
        trailer.removeItem(COSName.F);
        // TODO fix this once encryption is implemented
        trailer.removeItem(COSName.ENCRYPT);
        trailer.setLong(COSName.SIZE, written.lastKey() + 1);
        writer().write("trailer".getBytes(Charsets.US_ASCII));
        writer().writeEOL();
        trailer.getCOSObject().accept(writer);
        writer().write("startxref".getBytes(Charsets.US_ASCII));
        writer().writeEOL();
        writer().write(Long.toString(startxref));
        writer().writeEOL();
        writer().write("%%EOF".getBytes(Charsets.US_ASCII));
        writer().writeEOL();
    }

    void writeXrefStream(COSDictionary trailer) throws IOException
    {
        long startxref = writer().offset();
        LOG.debug("Writing xref stream at offset " + startxref);
        XrefEntry entry = XrefEntry.inUseEntry(written.lastKey() + 1, startxref, 0);
        written.put(entry.getObjectNumber(), entry);
        doWriteObject(new IndirectCOSObjectReference(entry.getObjectNumber(),
                entry.getGenerationNumber(), new XrefStream(trailer, written)));
        writer().write("startxref".getBytes(Charsets.US_ASCII));
        writer().writeEOL();
        writer().write(Long.toString(startxref));
        writer().writeEOL();
        writer().write("%%EOF".getBytes(Charsets.US_ASCII));
        writer().writeEOL();
    }

    private BufferedCountingChannelWriter writer()
    {
        return writer.writer();
    }

    @Override
    public void close() throws IOException
    {
        written.clear();
        IOUtils.close(writer);
    }
}
