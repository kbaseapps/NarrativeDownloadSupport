package narrativedownloadsupport;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import com.fasterxml.jackson.core.io.IOContext;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import us.kbase.auth.AuthException;
import us.kbase.auth.AuthService;
import us.kbase.auth.AuthToken;
import us.kbase.common.service.UObject;
import us.kbase.shock.client.BasicShockClient;
import us.kbase.shock.client.ShockNodeId;
import us.kbase.workspace.GetObjects2Params;
import us.kbase.workspace.ObjectData;
import us.kbase.workspace.ObjectSpecification;
import us.kbase.workspace.WorkspaceClient;

public class DownloadServlet extends HttpServlet {
	private static final long serialVersionUID = -1L;
	
	private static final String TOKEN_COOKIE_NAME = "kbase_session";
	
	private static Map<String, String> config = null;
    private static File tempDir = null;
    private static String wsUrl = null;
    private static String shockUrl = null;
    private static SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HHmmssSSS");

    private static Map<String, String> getConfig() throws Exception {
        if (config == null)
            config = new NarrativeDownloadSupportServer().getConfig();
        return config;
    }
    
    private static File getTempDir() throws Exception {
		if (tempDir == null)
			tempDir = new File(getConfig().get("scratch"));
		return tempDir;
    }

    private static String getWsUrl() throws Exception {
		if (wsUrl == null)
			wsUrl = getConfig().get("workspace-url");
		return wsUrl;
    }

    private static String getShockUrl() throws Exception {
		if (shockUrl == null)
			shockUrl = getConfig().get("shock-url");
		return shockUrl;
    }

	@Override
	protected void doOptions(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		setupResponseHeaders(request, response);
		response.setContentLength(0);
		response.getOutputStream().print("");
		response.getOutputStream().flush();
	}

	private static void setupResponseHeaders(HttpServletRequest request,
			HttpServletResponse response) {
		response.setHeader("Access-Control-Allow-Origin", "*");
		String allowedHeaders = request.getHeader("HTTP_ACCESS_CONTROL_REQUEST_HEADERS");
		response.setHeader("Access-Control-Allow-Headers", allowedHeaders);
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		doPost(request, response);
	}
	
	@SuppressWarnings("resource")
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		File f = null;
		try {
			AuthToken token = getToken(request);
			String url = request.getParameter("url");
			String ws = request.getParameter("ws");
			String name = request.getParameter("name");
			String id = getNotNull(request, "id");
			String wsZip = request.getParameter("wszip");
			String shockUnzipSuffix = request.getParameter("unzip");
			String shockNeedDeletion = request.getParameter("del");
			response.setContentType("application/octet-stream");
			if (ws != null) {
				boolean zipWithProv = wsZip != null && !(wsZip.equals("false") || wsZip.equals("0"));
				if (url == null)
					url = getWsUrl();
				WorkspaceClient wc = createWsClient(token, url);
				String fileName = removeWeirdChars(id);
				f = File.createTempFile("download_" + fileName, ".json", getTempDir());
				wc._setFileForNextRpcResponse(f);
				ObjectData oData = wc.getObjects2(new GetObjects2Params().withObjects(
				        Arrays.asList(new ObjectSpecification().withRef(ws + "/" + id))))
				        .getData().get(0);
				UObject data = oData.getData();
				setupResponseHeaders(request, response);
				if (name == null)
					name = fileName + (zipWithProv ? ".zip" : ".json");
				response.setHeader( "Content-Disposition", "attachment;filename=" + removeWeirdChars(name));
				if (zipWithProv) {
					Map<String, Object> superInfo = new LinkedHashMap<String, Object>();
					superInfo.put("metadata", Arrays.asList(oData.getInfo()));
					Map<String, Object> prov = new LinkedHashMap<String, Object>();
					prov.put("copy_source_inaccessible", oData.getAdditionalProperties().get("copy_source_inaccessible"));
					prov.put("created", oData.getCreated());
					prov.put("creator", oData.getCreator());
					prov.put("extracted_ids", oData.getAdditionalProperties().get("extracted_ids"));
					prov.put("info", oData.getInfo());
					prov.put("provenance", oData.getProvenance());
					prov.put("refs", oData.getRefs());
					superInfo.put("provenance", Arrays.asList(prov));
					superInfo = UObject.transformStringToObject(sortJsonKeys(UObject.transformObjectToString(superInfo)), 
							new TypeReference<Map<String, Object>>() {});
					ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(response.getOutputStream()));
					zos.putNextEntry(new ZipEntry("KBase_object_details_" + fileName + sdf.format(new Date()) + ".json"));
					zos.write(writeJsonAsPrettyString(superInfo).getBytes(Charset.forName("utf-8")));
					zos.closeEntry();
					zos.putNextEntry(new ZipEntry(fileName + ".json"));
					data.write(zos);
					zos.closeEntry();
					zos.close();
				} else {
					data.write(response.getOutputStream());
				}
			} else {
				if (url == null)
					url = getShockUrl();
				BasicShockClient client = createShockClient(token, url);
				try {
					setupResponseHeaders(request, response);
					if (shockUnzipSuffix == null) {
						if (name == null)
							name = id + ".node";
						response.setHeader( "Content-Disposition", "attachment;filename=" + name);
						client.getFile(new ShockNodeId(id), response.getOutputStream());
					} else {
						shockUnzipSuffix = shockUnzipSuffix.toLowerCase();
						f = File.createTempFile("download_" + removeWeirdChars(id), ".zip", getTempDir());
						FileOutputStream fos = new FileOutputStream(f);
						client.getFile(new ShockNodeId(id), fos);
						fos.close();
						ZipInputStream zis = new ZipInputStream(new FileInputStream(f));
						try {
							while (true) {
								ZipEntry ze = zis.getNextEntry();
								if (ze == null) {
									// we didn't find necessary entry, throw an error
									// zis will be closed in finally block anyway
									throw new IllegalStateException("Can't find entry name matching " +
											"[" + shockUnzipSuffix + "] suffix in zip file for shock node " + id);
								}
								if (ze.getName().toLowerCase().endsWith(shockUnzipSuffix)) {
									if (name == null) {
										name = ze.getName();
										if (name.contains("/"))
											name = name.substring(name.lastIndexOf('/') + 1);
									}
									response.setHeader( "Content-Disposition", "attachment;filename=" + removeWeirdChars(name));
									OutputStream os = response.getOutputStream();
									byte[] buffer = new byte[10000];
									while (true) {
										int len = zis.read(buffer);
										if (len <= 0)
											break;
										os.write(buffer, 0, len);
									}
									os.flush();
									buffer = null;
									break;
								}
							}
						} finally {
							try { zis.close(); } catch (Exception ignore) {}
						}
					}
				} finally {
					if (shockNeedDeletion != null && !(shockNeedDeletion.equals("false") || shockNeedDeletion.equals("0")))
						try {
							client.deleteNode(new ShockNodeId(id));
						} catch (Exception ignore) {
							ignore.printStackTrace();
						}
				}
			}
		} catch (ServletException ex) {
			throw ex;
		} catch (IOException ex) {
			throw ex;
		} catch (Throwable ex) {
			throw new ServletException(ex.getMessage(), ex);
		} finally {
			if (f != null && f.exists())
				try { f.delete(); } catch (Exception ignore) {}
		}
	}
	
	private AuthToken getToken(final HttpServletRequest request)
            throws IOException, AuthException {
        final String at = request.getHeader("Authorization");
        if (at != null && !at.trim().isEmpty()) {
            return AuthService.validateToken(at);
        }
        if (request.getCookies() != null) {
            for (final Cookie c: request.getCookies()) {
                if (c.getName().equals(TOKEN_COOKIE_NAME) &&
                        !c.getValue().isEmpty()) {
                    return AuthService.validateToken(
                            unmungeCookiePerShane(c.getValue()));
                }
            }
        }
        return null;
    }
	
	private static String unmungeCookiePerShane(final String cookie)
            throws AuthException {
        final String unenc;
        try {
            unenc = URLDecoder.decode(cookie, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("This should be impossible", e);
        }
        final Map<String, String> contents = new HashMap<String, String>();
        for (final String part: unenc.split("\\|")) {
            final String[] partpart = part.split("=");
            if (partpart.length != 2) {
                throw new AuthException("Cannot parse token from cookie: " +
                        "Subportion of cookie missing value");
            }
            contents.put(partpart[0], partpart[1]);
        }
        final String token = contents.get("token");
        if (token == null) {
            throw new AuthException("Cannot parse token from cookie: " +
                    "No token section");
        }
        return token.replace("PIPESIGN", "|").replace("EQUALSSIGN", "=");
    }
	
	private static String sortJsonKeys(String json) throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
		TreeNode schemaTree = mapper.readTree(json);
		Object schemaMap = mapper.treeToValue(schemaTree, Object.class);
		mapper.configure(SerializationFeature.INDENT_OUTPUT, true);
		return mapper.writeValueAsString(schemaMap);
	}
	
	private static String writeJsonAsPrettyString(Object obj) throws Exception {
		class PrettyPrinter extends DefaultPrettyPrinter {
			private static final long serialVersionUID = 1895256140742120450L;
			public PrettyPrinter() {
		        _arrayIndenter = Lf2SpacesIndenter.instance;
		    }
		}
		ObjectMapper mapper = new ObjectMapper(new JsonFactory() {
			private static final long serialVersionUID = -8057961034304889628L;
			@Override
		    protected JsonGenerator _createGenerator(Writer out, IOContext ctxt) throws IOException {
		        return super._createGenerator(out, ctxt).setPrettyPrinter(new PrettyPrinter());
		    }
		});
		mapper.configure(SerializationFeature.INDENT_OUTPUT, true);
		return mapper.writeValueAsString(obj);
	}
	
	private static String removeWeirdChars(String text) {
		StringBuilder ret = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (Character.isLetter(ch) || Character.isDigit(ch) || ch == '.') {
				ret.append(ch);
			} else {
				ret.append('_');
			}
		}
		return ret.toString();
	}
	
	private static String getNotNull(HttpServletRequest request, String param) throws ServletException {
		String value = request.getParameter(param);
		if (value == null)
			throw new ServletException("Parameter [" + param + "] wasn't defined");
		return value;
	}

    public static long copy(InputStream from, OutputStream to) throws IOException {
    	byte[] buf = new byte[10000];
    	long total = 0;
    	while (true) {
    		int r = from.read(buf);
    		if (r == -1) {
    			break;
    		}
    		to.write(buf, 0, r);
    		total += r;
    	}
    	return total;
    }

	public static WorkspaceClient createWsClient(AuthToken token, String wsUrl) throws Exception {
		WorkspaceClient ret;
		if (token == null) {
			ret = new WorkspaceClient(new URL(wsUrl));
		} else {
			ret = new WorkspaceClient(new URL(wsUrl), token);
			ret.setIsInsecureHttpConnectionAllowed(true);
		}
		return ret;
	}
	
	public static BasicShockClient createShockClient(AuthToken token, String url) throws Exception {
		BasicShockClient ret;
		if (token == null) {
			ret = new BasicShockClient(new URL(url));
		} else {
			ret = new BasicShockClient(new URL(url), token);
		}
		return ret;
	}

	public static WorkspaceClient createWsClient(AuthToken token) throws Exception {
		String wsUrl = getWsUrl();
		return createWsClient(token, wsUrl);
	}

	public static void main(String[] args) throws Exception {
		int port = 18888;
		if (args.length == 1)
			port = Integer.parseInt(args[0]);
		Server jettyServer = new Server(port);
		ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
		context.setContextPath("/");
		jettyServer.setHandler(context);
		context.addServlet(new ServletHolder(new DownloadServlet()),"/download");
		jettyServer.start();
		jettyServer.join();
	}
}
