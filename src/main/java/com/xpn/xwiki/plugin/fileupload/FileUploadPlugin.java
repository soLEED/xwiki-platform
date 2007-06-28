/*
 * Copyright 2006-2007, XpertNet SARL, and individual contributors as indicated
 * by the contributors.txt.
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

package com.xpn.xwiki.plugin.fileupload;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUpload;
import org.apache.commons.fileupload.FileUploadBase;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.RequestContext;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.fileupload.servlet.ServletRequestContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.api.Api;
import com.xpn.xwiki.plugin.XWikiDefaultPlugin;
import com.xpn.xwiki.plugin.XWikiPluginInterface;

/**
 * Plugin that offers access to uploaded files. The uploaded files are automatically parsed and
 * preserved as a list of {@link FileItem}s.
 * 
 * @version $Id: $
 */
public class FileUploadPlugin extends XWikiDefaultPlugin implements XWikiPluginInterface
{
    /**
     * The name of the plugin; the key that can be used to retrieve this plugin from the context.
     * 
     * @see XWikiPluginInterface#getName()
     */
    public static final String PLUGIN_NAME = "fileupload";

    /**
     * The context name of the uploaded file list. It can be used to retrieve the list of uploaded
     * files from the context.
     */
    public static final String FILE_LIST_KEY = "fileuploadlist";

    /**
     * The name of the parameter that can be set in the global XWiki preferences to override the
     * default maximum file size.
     */
    public static final String UPLOAD_MAXSIZE_PARAMETER = "upload_maxsize";

    /**
     * The name of the parameter that can be set in the global XWiki preferences to override the
     * default size threshold for on-disk storage.
     */
    public static final String UPLOAD_SIZETHRESHOLD_PARAMETER = "upload_sizethreshold";

    /**
     * Log object to log messages in this class.
     */
    private static final Log LOG = LogFactory.getLog(FileUploadPlugin.class);

    /**
     * The default maximum size for uploaded documents. This limit can be changed using the
     * <tt>upload_maxsize</tt> XWiki preference.
     */
    private static final long UPLOAD_DEFAULT_MAXSIZE = 10000000L;

    /**
     * The default maximum size for in-memory stored uploaded documents. If a file is larger than
     * this limit, it will be stored on disk until the current request finishes. This limit can be
     * changed using the <tt>upload_sizethreshold</tt> XWiki preference.
     */
    private static final long UPLOAD_DEFAULT_SIZETHRESHOLD = 100000L;

    /**
     * {@inheritDoc}
     * 
     * @see XWikiDefaultPlugin#XWikiDefaultPlugin(String,String,com.xpn.xwiki.XWikiContext)
     */
    public FileUploadPlugin(String name, String className, XWikiContext context)
    {
        super(name, className, context);
    }

    /**
     * {@inheritDoc}
     * 
     * @see com.xpn.xwiki.plugin.XWikiDefaultPlugin#getName()
     */
    public String getName()
    {
        return PLUGIN_NAME;
    }

    /**
     * {@inheritDoc}
     * 
     * @see com.xpn.xwiki.plugin.XWikiDefaultPlugin#init(XWikiContext)
     */
    public void init(XWikiContext context)
    {
        super.init(context);
    }

    /**
     * {@inheritDoc}
     * 
     * @see com.xpn.xwiki.plugin.XWikiDefaultPlugin#virtualInit(XWikiContext)
     */
    public void virtualInit(XWikiContext context)
    {
        super.virtualInit(context);
    }

    /**
     * {@inheritDoc}
     * 
     * @see com.xpn.xwiki.plugin.XWikiDefaultPlugin#getPluginApi(XWikiPluginInterface, XWikiContext)
     */
    public Api getPluginApi(XWikiPluginInterface plugin, XWikiContext context)
    {
        return new FileUploadPluginApi((FileUploadPlugin) plugin, context);
    }

    /**
     * {@inheritDoc}
     * 
     * Make sure we don't leave files in temp directories and in memory.
     */
    public void endRendering(XWikiContext context)
    {
        cleanFileList(context);
    }

    /**
     * Deletes all temporary files of the upload.
     * 
     * @param context Context of the request.
     * @see FileUploadPluginApi#cleanFileList()
     */
    public void cleanFileList(XWikiContext context)
    {
        List fileuploadlist = (List) context.get(FILE_LIST_KEY);
        if (fileuploadlist != null) {
            for (int i = 0; i < fileuploadlist.size(); i++) {
                try {
                    FileItem item = (FileItem) fileuploadlist.get(i);
                    item.delete();
                } catch (Exception ex) {
                    LOG.warn("Exception cleaning uploaded files", ex);
                }
            }
            context.remove(FILE_LIST_KEY);
        }
    }

    /**
     * Loads the list of uploaded files in the context if there are any uploaded files.
     * 
     * @param context Context of the request.
     * @throws XWikiException An XWikiException is thrown if the request could not be parsed.
     * @see FileUploadPluginApi#loadFileList()
     */
    public void loadFileList(XWikiContext context) throws XWikiException
    {
        XWiki xwiki = context.getWiki();
        loadFileList(xwiki.getXWikiPreferenceAsLong(
            UPLOAD_MAXSIZE_PARAMETER,
            UPLOAD_DEFAULT_MAXSIZE, context),
            (int) xwiki.getXWikiPreferenceAsLong(UPLOAD_SIZETHRESHOLD_PARAMETER,
                UPLOAD_DEFAULT_SIZETHRESHOLD, context),
            xwiki.Param("xwiki.upload.tempdir"),
            context);
    }

    /**
     * Loads the list of uploaded files in the context if there are any uploaded files.
     * 
     * @param uploadMaxSize Maximum size of the uploaded files.
     * @param uploadSizeThreashold Threashold over which the file data should be stored on disk, and
     *            not in memory.
     * @param tempdir Temporary directory to store the uploaded files that are not kept in memory.
     * @param context Context of the request.
     * @throws XWikiException if the request could not be parsed, or the maximum file size was
     *             reached.
     * @see FileUploadPluginApi#loadFileList(long, int, String)
     */
    public void loadFileList(long uploadMaxSize, int uploadSizeThreashold, String tempdir,
        XWikiContext context) throws XWikiException
    {
        // Get the FileUpload Data
        DiskFileItemFactory factory = new DiskFileItemFactory();
        factory.setSizeThreshold(uploadSizeThreashold);

        if (tempdir != null) {
            File tempdirFile = new File(tempdir);
            if (tempdirFile.mkdirs() && tempdirFile.canWrite()) {
                factory.setRepository(tempdirFile);
            }
        }

        // TODO: Does this work in portlet mode, or we must use PortletFileUpload?
        FileUpload fileupload = new ServletFileUpload(factory);
        RequestContext reqContext =
            new ServletRequestContext(context.getRequest().getHttpServletRequest());
        fileupload.setSizeMax(uploadMaxSize);
        // context.put("fileupload", fileupload);

        try {
            List list = fileupload.parseRequest(reqContext);
            // We store the file list in the context
            context.put(FILE_LIST_KEY, list);
        } catch (FileUploadBase.SizeLimitExceededException e) {
            throw new XWikiException(XWikiException.MODULE_XWIKI_APP,
                XWikiException.ERROR_XWIKI_APP_FILE_EXCEPTION_MAXSIZE,
                "Exception uploaded file");
        } catch (FileUploadException e) {
            throw new XWikiException(XWikiException.MODULE_XWIKI_APP,
                XWikiException.ERROR_XWIKI_APP_UPLOAD_PARSE_EXCEPTION,
                "Exception while parsing uploaded file",
                e);
        }
    }

    /**
     * Allows to retrieve the current list of uploaded files, as a list of {@link FileItem}s.
     * {@link #loadFileList(XWikiContext)} needs to be called beforehand
     * 
     * @param context Context of the request.
     * @return A list of FileItem elements.
     * @see FileUploadPluginApi#getFileItems()
     */
    public List getFileItems(XWikiContext context)
    {
        return (List) context.get(FILE_LIST_KEY);
    }

    /**
     * Allows to retrieve the contents of an uploaded file as a sequence of bytes.
     * {@link #loadFileList(XWikiContext)} needs to be called beforehand.
     * 
     * @param formfieldName The name of the form field.
     * @param context Context of the request.
     * @return The contents of the file.
     * @throws XWikiException if the data could not be read.
     * @see FileUploadPluginApi#getFileItemData(String)
     */
    public byte[] getFileItemData(String formfieldName, XWikiContext context)
        throws XWikiException
    {
        FileItem fileitem = getFile(formfieldName, context);

        if (fileitem == null) {
            return null;
        }

        byte[] data = new byte[(int) fileitem.getSize()];
        try {
            InputStream fileis = fileitem.getInputStream();
            if (fileis != null) {
                fileis.read(data);
                fileis.close();
            }
        } catch (java.lang.OutOfMemoryError e) {
            throw new XWikiException(XWikiException.MODULE_XWIKI_APP,
                XWikiException.ERROR_XWIKI_APP_JAVA_HEAP_SPACE,
                "Java Heap Space, Out of memory exception",
                e);
        } catch (IOException ie) {
            throw new XWikiException(XWikiException.MODULE_XWIKI_APP,
                XWikiException.ERROR_XWIKI_APP_UPLOAD_FILE_EXCEPTION,
                "Exception while reading uploaded parsed file",
                ie);
        }
        return data;
    }

    /**
     * Allows to retrieve the contents of an uploaded file as a string.
     * {@link #loadFileList(XWikiContext)} needs to be called beforehand.
     * 
     * @param formfieldName The name of the form field.
     * @param context Context of the request.
     * @return The contents of the file.
     * @throws XWikiException if the data could not be read.
     * @see FileUploadPluginApi#getFileItemAsString(String)
     */
    public String getFileItemAsString(String formfieldName, XWikiContext context)
        throws XWikiException
    {
        byte[] data = getFileItemData(formfieldName, context);
        if (data == null) {
            return null;
        }
        return new String(data);
    }

    /**
     * Allows to retrieve the contents of an uploaded file as a string.
     * {@link #loadFileList(XWikiContext)} needs to be called beforehand.
     * 
     * @deprecated not well named, use
     *             {@link #getFileItemAsString(String, com.xpn.xwiki.XWikiContext)}
     * @param formfieldName The name of the form field.
     * @param context Context of the request.
     * @return The contents of the file.
     * @throws XWikiException Exception is thrown if the data could not be read.
     * @see FileUploadPluginApi#getFileItemAsString(String)
     */
    public String getFileItem(String formfieldName, XWikiContext context) throws XWikiException
    {
        return getFileItemAsString(formfieldName, context);
    }

    /**
     * Retrieves the list of FileItem names. {@link #loadFileList(XWikiContext)} needs to be called
     * beforehand.
     * 
     * @param context Context of the request
     * @return List of strings of the item names
     */
    public List getFileItemNames(XWikiContext context)
    {
        List itemnames = new ArrayList();
        List fileuploadlist = getFileItems(context);
        if (fileuploadlist == null) {
            return itemnames;
        }

        for (int i = 0; i < fileuploadlist.size(); i++) {
            FileItem item = (FileItem) fileuploadlist.get(i);
            itemnames.add(item.getFieldName());
        }
        return itemnames;
    }

    /**
     * Get the name of the file uploaded for a form field.
     * 
     * @param formfieldName The name of the form field.
     * @param context Context of the request.
     * @return The file name, or <tt>null</tt> if no file was uploaded for that form field.
     */
    public String getFileName(String formfieldName, XWikiContext context)
    {
        FileItem fileitem = getFile(formfieldName, context);

        return (fileitem == null) ? null : fileitem.getName();
    }

    /**
     * Return the FileItem corresponding to the file uploaded for a form field.
     * 
     * @param formfieldName The name of the form field.
     * @param context Context of the request.
     * @return The corresponding FileItem, or <tt>null</tt> if no file was uploaded for that form
     *         field.
     */
    public FileItem getFile(String formfieldName, XWikiContext context)
    {
        List fileuploadlist = getFileItems(context);
        if (fileuploadlist == null) {
            return null;
        }

        FileItem fileitem = null;
        for (int i = 0; i < fileuploadlist.size(); i++) {
            FileItem item = (FileItem) fileuploadlist.get(i);
            if (formfieldName.equals(item.getFieldName())) {
                fileitem = item;
                break;
            }
        }

        return fileitem;
    }
}
