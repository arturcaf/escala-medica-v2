package com.escala;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import com.sun.net.httpserver.HttpHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class EscalaServer {
   static final int PORT = System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) : 8080;
   static final double HOSP_LAT = -12.7010338;
   static final double HOSP_LON = -38.3324179;
   static final double CHECKIN_RADIUS_KM = 2.0;
   static final String ADMIN_CRM = "27140";
   // Senha do admin antes de ele criar a propria. Pode ser trocada pela variavel ADMIN_SENHA.
   static final String ADMIN_SENHA_INICIAL = "71991402300";
   static final int SENHA_MIN = 6;
   static final int MAX_FALHAS = 5;
   static final long BLOQUEIO_MS = 10 * 60 * 1000L;
   static final String ADMIN_NOME = "Artur Cesar";
   static final String CALLMEBOT_URL = "https://api.callmebot.com/whatsapp.php";
   static List<String[]> escala = new ArrayList<>();
   static List<Map<String, String>> medicos = new ArrayList<>();
   static Connection db;
   static Path dataDir;
   static String frontendHtml = "";
   static Map<String, Map<String, String>> sessions = new ConcurrentHashMap<>();
   static Map<String, long[]> loginFalhas = new ConcurrentHashMap<>();
   static final SecureRandom RANDOM = new SecureRandom();
   static AtomicInteger trocaCounter = new AtomicInteger(1);
   static AtomicInteger medicoIdCounter = new AtomicInteger(19);
   static final List<HttpExchange> sseClients = Collections.synchronizedList(new ArrayList<>());

   public static void main(String[] var0) throws Exception {
      System.out.println("\n  ╔══════════════════════════════════════════╗");
      System.out.println("  ║   ESCALA MEDICA v7 - CRUD Medicos        ║");
      System.out.println("  ║   WhatsApp | SQLite | SSE | GPS          ║");
      System.out.println("  ╚══════════════════════════════════════════╝\n");
      String var1 = EscalaServer.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
      Path var2 = Paths.get(var1).getParent();
      dataDir = var2.resolve("data");
      if (!Files.exists(dataDir)) {
         dataDir = Paths.get("data");
      }

      if (!Files.exists(dataDir)) {
         Files.createDirectories(dataDir);
      }

      initDatabase();
      loadEscala();
      loadMedicos();
      loadFrontend(var2);
      System.out.println("  Escala: " + escala.size() + " plantoes");
      System.out.println("  Medicos: " + medicos.size() + " cadastrados");
      System.out.println("  Banco: " + dataDir.resolve("escala.db").toAbsolutePath());
      HttpServer var3 = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);
      var3.createContext("/api/login", EscalaServer::handleLogin);
      var3.createContext("/api/logout", EscalaServer::handleLogout);
      var3.createContext("/api/me", EscalaServer::handleMe);
      var3.createContext("/api/senha", EscalaServer::handleSenha);
      var3.createContext("/api/escala", autenticado(EscalaServer::handleEscala));
      var3.createContext("/api/medicos", autenticado(EscalaServer::handleMedicos));
      var3.createContext("/api/trocas", autenticado(EscalaServer::handleTrocas));
      var3.createContext("/api/checkin", autenticado(EscalaServer::handleCheckin));
      var3.createContext("/api/notificacoes", autenticado(EscalaServer::handleNotificacoes));
      var3.createContext("/api/whatsapp", autenticado(EscalaServer::handleWhatsApp));
      var3.createContext("/api/cadastro-forms", EscalaServer::handleCadastroForms);
      var3.createContext("/api/stats", EscalaServer::handleStats);
      var3.createContext("/api/events", autenticado(EscalaServer::handleSSE));
      var3.createContext("/", EscalaServer::handleStatic);
      var3.setExecutor(Executors.newFixedThreadPool(16));
      var3.start();
      System.out.println("\n  Servidor: http://localhost:" + PORT);
      System.out.println("  Admin: CRM 27140\n");
      scheduleReminders();
   }

   static void initDatabase() throws Exception {
      Path var0 = dataDir.resolve("escala.db");
      Class.forName("org.sqlite.JDBC");
      db = DriverManager.getConnection("jdbc:sqlite:" + var0.toAbsolutePath());
      db.setAutoCommit(true);
      Statement var1 = db.createStatement();
      var1.execute(
         "CREATE TABLE IF NOT EXISTS trocas (id TEXT PRIMARY KEY, plantao_id TEXT, data_plantao TEXT, dia_plantao TEXT, turno TEXT, plantonista_original TEXT, solicitante TEXT, substituto TEXT, motivo TEXT, obs TEXT DEFAULT '', status TEXT DEFAULT 'PENDENTE', data_solicitacao TEXT, obs_gestor TEXT DEFAULT '', data_decisao TEXT DEFAULT '')"
      );
      var1.execute(
         "CREATE TABLE IF NOT EXISTS checkins (id TEXT PRIMARY KEY, tipo TEXT, medico TEXT, plantao_id TEXT DEFAULT '', lat TEXT, lon TEXT, distancia_km TEXT, data TEXT)"
      );
      var1.execute(
         "CREATE TABLE IF NOT EXISTS notificacoes (id INTEGER PRIMARY KEY AUTOINCREMENT, tipo TEXT, titulo TEXT, mensagem TEXT, data TEXT, lida INTEGER DEFAULT 0)"
      );
      var1.execute("CREATE TABLE IF NOT EXISTS escala_overrides (plantao_id TEXT PRIMARY KEY, plantonista TEXT, status TEXT, turno TEXT, updated_at TEXT)");
      var1.execute("CREATE TABLE IF NOT EXISTS senhas (usuario TEXT PRIMARY KEY, hash TEXT NOT NULL, updated_at TEXT)");
      var1.execute("CREATE TABLE IF NOT EXISTS whatsapp_keys (medico TEXT PRIMARY KEY, telefone TEXT, apikey TEXT, ativo INTEGER DEFAULT 1, created_at TEXT)");
      var1.execute(
         "CREATE TABLE IF NOT EXISTS medicos_db (id INTEGER PRIMARY KEY, nome TEXT NOT NULL, telefone TEXT, funcao TEXT DEFAULT 'Plantonista', crm TEXT DEFAULT '', email TEXT DEFAULT '', ativo INTEGER DEFAULT 1, created_at TEXT, updated_at TEXT)"
      );
      var1.close();
      ResultSet var2 = db.createStatement().executeQuery("SELECT MAX(CAST(REPLACE(id,'TRK-','') AS INTEGER)) FROM trocas");
      if (var2.next() && var2.getObject(1) != null) {
         trocaCounter.set(var2.getInt(1) + 1);
      }

      var2.close();
      System.out.println("  Banco SQLite: " + var0.getFileName());
   }

   static void loadEscala() throws Exception {
      Path var0 = dataDir.resolve("escala.json");
      if (!Files.exists(var0)) {
         try (InputStream var1 = EscalaServer.class.getResourceAsStream("/data/escala.json")) {
            if (var1 != null) {
               Files.copy(var1, var0);
            }
         }
      }

      if (Files.exists(var0)) {
         escala = parseJsonArrayOfArrays(Files.readString(var0));
         ResultSet var7 = db.createStatement().executeQuery("SELECT * FROM escala_overrides");

         while (var7.next()) {
            String var2 = var7.getString("plantao_id");

            for (String[] var4 : escala) {
               if (var4[0].equals(var2)) {
                  if (var7.getString("plantonista") != null) {
                     var4[5] = var7.getString("plantonista");
                  }

                  if (var7.getString("status") != null) {
                     var4[6] = var7.getString("status");
                  }

                  if (var7.getString("turno") != null) {
                     var4[3] = var7.getString("turno");
                  }
                  break;
               }
            }
         }

         var7.close();
      }
   }

   static void loadMedicos() throws Exception {
      ResultSet var0 = db.createStatement().executeQuery("SELECT COUNT(*) FROM medicos_db WHERE ativo=1");
      int var1 = var0.next() ? var0.getInt(1) : 0;
      var0.close();
      if (var1 > 0) {
         medicos = getMedicosFromDB();
         int var2 = medicos.stream().mapToInt(var0x -> Integer.parseInt(var0x.getOrDefault("id", "0"))).max().orElse(18);
         medicoIdCounter.set(var2 + 1);
         System.out.println("  medicos carregados do banco: " + medicos.size());
      } else {
         Path var11 = dataDir.resolve("medicos.json");
         if (!Files.exists(var11)) {
            try (InputStream var3 = EscalaServer.class.getResourceAsStream("/data/medicos.json")) {
               if (var3 != null) {
                  Files.copy(var3, var11);
               }
            }
         }

         if (Files.exists(var11)) {
            List<String[]> var12 = parseJsonArrayOfArrays(Files.readString(var11));
            int var4 = 0;

            for (String[] var6 : var12) {
               int var7 = Integer.parseInt(var6[0]);
               if (var7 > var4) {
                  var4 = var7;
               }

               PreparedStatement var8 = db.prepareStatement(
                  "INSERT OR IGNORE INTO medicos_db (id,nome,telefone,funcao,crm,email,ativo,created_at,updated_at) VALUES (?,?,?,?,?,?,1,?,?)"
               );
               var8.setInt(1, var7);
               var8.setString(2, var6[1]);
               var8.setString(3, var6[2]);
               var8.setString(4, var6[3]);
               var8.setString(5, var6[4]);
               var8.setString(6, var6[5]);
               var8.setString(7, now());
               var8.setString(8, now());
               var8.executeUpdate();
               var8.close();
            }

            medicoIdCounter.set(var4 + 1);
            medicos = getMedicosFromDB();
            System.out.println("  medicos importados do JSON para banco: " + medicos.size());
         }
      }
   }

   static List<Map<String, String>> getMedicosFromDB() throws Exception {
      ResultSet var0 = db.createStatement().executeQuery("SELECT * FROM medicos_db WHERE ativo=1 ORDER BY nome");
      ArrayList var1 = new ArrayList();

      while (var0.next()) {
         LinkedHashMap var2 = new LinkedHashMap();
         var2.put("id", String.valueOf(var0.getInt("id")));
         var2.put("nome", var0.getString("nome"));
         var2.put("telefone", var0.getString("telefone") != null ? var0.getString("telefone") : "");
         var2.put("funcao", var0.getString("funcao") != null ? var0.getString("funcao") : "Plantonista");
         var2.put("crm", var0.getString("crm") != null ? var0.getString("crm") : "");
         var2.put("email", var0.getString("email") != null ? var0.getString("email") : "");
         var2.put("ativo", String.valueOf(var0.getInt("ativo")));
         var1.add(var2);
      }

      var0.close();
      return var1;
   }

   static void saveMedicosToJson() {
      try {
         List var0 = medicos.stream()
            .map(var0x -> new String[]{var0x.get("id"), var0x.get("nome"), var0x.get("telefone"), var0x.get("funcao"), var0x.get("crm"), var0x.get("email")})
            .collect(Collectors.toList());
         Files.writeString(dataDir.resolve("medicos.json"), arraysToJson(var0));
      } catch (Exception var1) {
         System.err.println("Erro ao salvar medicos.json: " + var1.getMessage());
      }
   }

   static void loadFrontend(Path var0) throws Exception {
      Path var1 = var0.resolve("index.html");
      if (Files.exists(var1)) {
         frontendHtml = Files.readString(var1);
      } else {
         try (InputStream var2 = EscalaServer.class.getResourceAsStream("/static/index.html")) {
            if (var2 != null) {
               frontendHtml = new String(var2.readAllBytes(), StandardCharsets.UTF_8);
               return;
            }
         }

         frontendHtml = "<html><body><h1>index.html nao encontrado</h1></body></html>";
      }
   }

   static void sendWhatsApp(String var0, String var1, String var2) {
      if (var0 != null && !var0.isEmpty() && var1 != null && !var1.isEmpty()) {
         new Thread(() -> {
            try {
               String var3 = var0.replaceAll("[^0-9]", "");
               String var4 = URLEncoder.encode(var2, StandardCharsets.UTF_8);
               String var5 = "https://api.callmebot.com/whatsapp.php?phone=" + var3 + "&text=" + var4 + "&apikey=" + var1;
               HttpURLConnection var6 = (HttpURLConnection)new URL(var5).openConnection();
               var6.setRequestMethod("GET");
               var6.setConnectTimeout(10000);
               var6.setReadTimeout(10000);
               int var7 = var6.getResponseCode();
               System.out.println("  WhatsApp -> " + var3 + " HTTP:" + var7);
               var6.disconnect();
            } catch (Exception var8) {
               System.err.println("  WhatsApp ERRO: " + var8.getMessage());
            }
         }).start();
      }
   }

   static String getApiKey(String var0) {
      try {
         PreparedStatement var1 = db.prepareStatement("SELECT apikey FROM whatsapp_keys WHERE medico=?");
         var1.setString(1, var0);
         ResultSet var2 = var1.executeQuery();
         String var3 = var2.next() ? var2.getString("apikey") : null;
         var2.close();
         var1.close();
         return var3;
      } catch (Exception var4) {
         return null;
      }
   }

   static String getTelefone(String var0) {
      return medicos.stream().filter(var1 -> var1.get("nome").equals(var0)).map(var0x -> var0x.get("telefone")).findFirst().orElse(null);
   }

   static void notificarMedico(String var0, String var1) {
      String var2 = getTelefone(var0);
      String var3 = getApiKey(var0);
      if (var2 != null && var3 != null) {
         sendWhatsApp(var2, var3, var1);
      }
   }

   static void scheduleReminders() {
      ScheduledExecutorService var0 = Executors.newScheduledThreadPool(1);
      LocalDateTime var1 = LocalDateTime.now();
      LocalDateTime var2 = var1.toLocalDate().atTime(8, 0);
      if (var1.isAfter(var2)) {
         var2 = var2.plusDays(1L);
      }

      long var3 = Duration.between(var1, var2).toMinutes();
      var0.scheduleAtFixedRate(
         () -> {
            try {
               String var0x = LocalDate.now().toString();
               String var1x = LocalDate.now().plusDays(7L).toString();
               List<String[]> var2x = escala.stream()
                  .filter(var2xx -> "A DEFINIR".equals(var2xx[6]) && var2xx[1].compareTo(var0x) >= 0 && var2xx[1].compareTo(var1x) <= 0)
                  .collect(Collectors.toList());
               if (!var2x.isEmpty()) {
                  String var3x = "\ud83c\udfe5 ESCALA MEDICA - ALERTA\nPlantoes SEM plantonista nos proximos 7 dias:\n";

                  for (String[] var5 : var2x) {
                     var3x = var3x + "• " + var5[1] + " (" + var5[2] + ") - " + var5[3] + "\n";
                  }

                  notificarMedico("Artur Cesar", var3x);
               }
            } catch (Exception var6) {
               System.err.println("Erro lembrete: " + var6.getMessage());
            }
         },
         var3,
         1440L,
         TimeUnit.MINUTES
      );
   }

   static void handleSSE(HttpExchange var0) throws IOException {
      var0.getResponseHeaders().set("Content-Type", "text/event-stream");
      var0.getResponseHeaders().set("Cache-Control", "no-cache");
      var0.getResponseHeaders().set("Connection", "keep-alive");
      var0.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
      var0.sendResponseHeaders(200, 0L);
      sseClients.add(var0);

      try {
         var0.getResponseBody().write("data: {\"type\":\"connected\"}\n\n".getBytes(StandardCharsets.UTF_8));
         var0.getResponseBody().flush();
      } catch (Exception var2) {
         sseClients.remove(var0);
      }
   }

   static void broadcast(String var0, String var1) {
      String var2 = "data: {\"type\":\"" + var0 + "\",\"data\":" + var1 + ",\"ts\":\"" + now() + "\"}\n\n";
      byte[] var3 = var2.getBytes(StandardCharsets.UTF_8);
      ArrayList var4 = new ArrayList();
      synchronized (sseClients) {
         for (HttpExchange var7 : sseClients) {
            try {
               var7.getResponseBody().write(var3);
               var7.getResponseBody().flush();
            } catch (Exception var10) {
               var4.add(var7);
            }
         }
      }

      sseClients.removeAll(var4);
   }

   static void addNotificacaoAndBroadcast(String var0, String var1, String var2) {
      try {
         PreparedStatement var3 = db.prepareStatement("INSERT INTO notificacoes (tipo,titulo,mensagem,data,lida) VALUES (?,?,?,?,0)");
         var3.setString(1, var0);
         var3.setString(2, var1);
         var3.setString(3, var2);
         var3.setString(4, now());
         var3.executeUpdate();
         var3.close();
      } catch (Exception var4) {
         System.err.println("Erro notif: " + var4.getMessage());
      }

      broadcast("notificacao", "{\"tipo\":\"" + escapeJson(var0) + "\",\"titulo\":\"" + escapeJson(var1) + "\",\"mensagem\":\"" + escapeJson(var2) + "\"}");
   }

   static String getToken(HttpExchange var0) {
      String var1 = var0.getRequestHeaders().getFirst("Authorization");
      if (var1 != null && var1.startsWith("Bearer ")) {
         return var1.substring(7);
      } else {
         String var2 = var0.getRequestHeaders().getFirst("Cookie");
         if (var2 != null) {
            for (String var6 : var2.split(";")) {
               var6 = var6.trim();
               if (var6.startsWith("token=")) {
                  return var6.substring(6);
               }
            }
         }

         String var7 = parseQuery(var0.getRequestURI().getQuery()).get("token");
         return var7 != null && !var7.isEmpty() ? var7 : null;
      }
   }

   static Map<String, String> getSession(HttpExchange var0) {
      String var1 = getToken(var0);
      return var1 != null ? sessions.get(var1) : null;
   }

   static boolean isAdmin(HttpExchange var0) {
      Map var1 = getSession(var0);
      return var1 != null && "true".equals(var1.get("isAdmin"));
   }

   static void handleStatic(HttpExchange var0) throws IOException {
      byte[] var1 = frontendHtml.getBytes(StandardCharsets.UTF_8);
      var0.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
      var0.sendResponseHeaders(200, (long)var1.length);
      var0.getResponseBody().write(var1);
      var0.getResponseBody().close();
   }

   static void handleLogin(HttpExchange var0) throws IOException {
      if (!"POST".equals(var0.getRequestMethod())) {
         send405(var0);
         return;
      }
      Map<String, String> var1 = parseJsonObject(new String(var0.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      String crm = var1.getOrDefault("crm", "").trim();
      String senha = var1.getOrDefault("senha", "").trim();
      if (crm.isEmpty() || senha.isEmpty()) {
         sendJson(var0, 400, "{\"error\":\"Preencha CRM/ID e senha\"}");
         return;
      }
      long[] falhas = loginFalhas.get(crm);
      if (falhas != null && falhas[0] >= MAX_FALHAS && System.currentTimeMillis() - falhas[1] < BLOQUEIO_MS) {
         sendJson(var0, 429, "{\"error\":\"Muitas tentativas erradas. Aguarde 10 minutos e tente de novo.\"}");
         return;
      }

      boolean admin = ADMIN_CRM.equals(crm);
      Map<String, String> medico = admin ? null : medicos.stream().filter(m -> crm.equals(m.get("id")) || crm.equals(m.get("crm"))).findFirst().orElse(null);
      String usuario = admin ? "admin" : (medico != null ? medico.get("id") : null);
      Boolean trocar = usuario == null ? null : conferirSenha(usuario, medico, senha);
      if (trocar == null) {
         long[] f = loginFalhas.computeIfAbsent(crm, k -> new long[2]);
         if (System.currentTimeMillis() - f[1] >= BLOQUEIO_MS) {
            f[0] = 0;
         }
         f[0]++;
         f[1] = System.currentTimeMillis();
         sendJson(var0, 401, "{\"error\":\"CRM/ID ou senha invalidos\"}");
         return;
      }
      loginFalhas.remove(crm);

      String token = UUID.randomUUID().toString();
      LinkedHashMap<String, String> sessao = new LinkedHashMap<>();
      sessao.put("nome", admin ? ADMIN_NOME : medico.get("nome"));
      sessao.put("crm", admin ? crm : medico.get("id"));
      sessao.put("isAdmin", admin ? "true" : "false");
      sessao.put("usuario", usuario);
      sessao.put("trocarSenha", trocar ? "true" : "false");
      sessao.put("token", token);
      sessions.put(token, sessao);
      long pendentes = countNotifPendentes();
      StringBuilder json = new StringBuilder("{\"token\":\"").append(token)
         .append("\",\"nome\":\"").append(escapeJson(sessao.get("nome")))
         .append("\",\"crm\":\"").append(escapeJson(sessao.get("crm")))
         .append("\",\"isAdmin\":").append(admin)
         .append(",\"notificacoesPendentes\":").append(pendentes)
         .append(",\"trocarSenha\":").append(trocar);
      if (!admin) {
         String apiKey = getApiKey(medico.get("nome"));
         json.append(",\"whatsappAtivo\":").append(apiKey != null && !apiKey.isEmpty());
      }
      sendJson(var0, 200, json.append("}").toString());
   }

   // Confere a senha. Devolve null se estiver errada, true se o usuario ainda
   // precisa criar a propria senha (primeiro acesso) e false se ja criou.
   static Boolean conferirSenha(String usuario, Map<String, String> medico, String senha) {
      String hash = getSenhaHash(usuario);
      if (hash != null) {
         return verificarHash(senha, hash) ? false : null;
      }
      if ("admin".equals(usuario)) {
         String env = System.getenv("ADMIN_SENHA");
         if (env != null && !env.isEmpty()) {
            return env.equals(senha) ? false : null;
         }
         return ADMIN_SENHA_INICIAL.equals(senha) ? true : null;
      }
      // Primeiro acesso: telefone completo, so numeros (com ou sem o 55 do pais)
      String tel = medico.get("telefone") == null ? "" : medico.get("telefone").replaceAll("[^0-9]", "");
      String dig = senha.replaceAll("[^0-9]", "");
      if (tel.length() < 10 || dig.length() < 10) {
         return null;
      }
      return dig.equals(tel) || ("55" + dig).equals(tel) ? true : null;
   }

   static String getSenhaHash(String usuario) {
      try (PreparedStatement ps = db.prepareStatement("SELECT hash FROM senhas WHERE usuario=?")) {
         ps.setString(1, usuario);
         ResultSet rs = ps.executeQuery();
         return rs.next() ? rs.getString(1) : null;
      } catch (Exception e) {
         System.err.println("Erro ao ler senha: " + e.getMessage());
         return null;
      }
   }

   static String gerarHash(String senha) throws Exception {
      byte[] salt = new byte[16];
      RANDOM.nextBytes(salt);
      int iter = 120000;
      return "pbkdf2$" + iter + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(pbkdf2(senha, salt, iter));
   }

   static boolean verificarHash(String senha, String guardado) {
      try {
         String[] p = guardado.split("\\$");
         byte[] salt = Base64.getDecoder().decode(p[2]);
         byte[] esperado = Base64.getDecoder().decode(p[3]);
         return java.security.MessageDigest.isEqual(esperado, pbkdf2(senha, salt, Integer.parseInt(p[1])));
      } catch (Exception e) {
         return false;
      }
   }

   static byte[] pbkdf2(String senha, byte[] salt, int iter) throws Exception {
      KeySpec spec = new PBEKeySpec(senha.toCharArray(), salt, iter, 256);
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
   }

   // POST /api/senha            {senhaAtual, novaSenha}  troca a propria senha
   // POST /api/senha/reset      {id}                     admin: volta o medico ao primeiro acesso
   static void handleSenha(HttpExchange var0) throws IOException {
      if (!"POST".equals(var0.getRequestMethod())) {
         send405(var0);
         return;
      }
      Map<String, String> sessao = getSession(var0);
      if (sessao == null) {
         sendJson(var0, 401, "{\"error\":\"Nao autenticado\"}");
         return;
      }
      Map<String, String> body = parseJsonObject(new String(var0.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      try {
         if (var0.getRequestURI().getPath().endsWith("/reset")) {
            if (!"true".equals(sessao.get("isAdmin"))) {
               sendJson(var0, 403, "{\"error\":\"Apenas o administrador pode resetar senhas\"}");
               return;
            }
            String id = body.getOrDefault("id", "");
            try (PreparedStatement ps = db.prepareStatement("DELETE FROM senhas WHERE usuario=?")) {
               ps.setString(1, id);
               ps.executeUpdate();
            }
            sessions.values().removeIf(s -> id.equals(s.get("usuario")));
            sendJson(var0, 200, "{\"ok\":true}");
            return;
         }

         String usuario = sessao.get("usuario");
         String nova = body.getOrDefault("novaSenha", "").trim();
         if (!"true".equals(sessao.get("trocarSenha"))) {
            Map<String, String> medico = "admin".equals(usuario) ? null : medicos.stream().filter(m -> usuario.equals(m.get("id"))).findFirst().orElse(null);
            if (conferirSenha(usuario, medico, body.getOrDefault("senhaAtual", "").trim()) == null) {
               sendJson(var0, 400, "{\"error\":\"Senha atual incorreta\"}");
               return;
            }
         }
         if (nova.length() < SENHA_MIN) {
            sendJson(var0, 400, "{\"error\":\"A nova senha precisa ter pelo menos " + SENHA_MIN + " caracteres\"}");
            return;
         }
         String digitos = nova.replaceAll("[^0-9]", "");
         boolean ehTelefone = digitos.length() >= 8 && medicos.stream().anyMatch(m -> {
            String t = m.get("telefone") == null ? "" : m.get("telefone").replaceAll("[^0-9]", "");
            return !t.isEmpty() && t.endsWith(digitos);
         });
         if (ehTelefone || ADMIN_SENHA_INICIAL.equals(nova)) {
            sendJson(var0, 400, "{\"error\":\"Nao use numero de telefone como senha\"}");
            return;
         }
         try (PreparedStatement ps = db.prepareStatement("INSERT OR REPLACE INTO senhas (usuario,hash,updated_at) VALUES (?,?,?)")) {
            ps.setString(1, usuario);
            ps.setString(2, gerarHash(nova));
            ps.setString(3, now());
            ps.executeUpdate();
         }
         String tokenAtual = getToken(var0);
         sessions.entrySet().removeIf(e -> usuario.equals(e.getValue().get("usuario")) && !e.getKey().equals(tokenAtual));
         sessao.put("trocarSenha", "false");
         sendJson(var0, 200, "{\"ok\":true}");
      } catch (Exception e) {
         sendJson(var0, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
      }
   }

   // Exige login; quem ainda esta com a senha de primeiro acesso so pode criar a nova senha.
   static HttpHandler autenticado(HttpHandler handler) {
      return ex -> {
         Map<String, String> sessao = getSession(ex);
         if (sessao == null) {
            sendJson(ex, 401, "{\"error\":\"Nao autenticado\"}");
            return;
         }
         if ("true".equals(sessao.get("trocarSenha"))) {
            sendJson(ex, 403, "{\"error\":\"Crie sua senha para continuar\",\"trocarSenha\":true}");
            return;
         }
         handler.handle(ex);
      };
   }

   static void handleLogout(HttpExchange var0) throws IOException {
      String var1 = getToken(var0);
      if (var1 != null) {
         sessions.remove(var1);
      }

      sendJson(var0, 200, "{\"ok\":true}");
   }

   static void handleMe(HttpExchange var0) throws IOException {
      Map var1 = getSession(var0);
      if (var1 == null) {
         sendJson(var0, 401, "{\"error\":\"Nao autenticado\"}");
      } else {
         long var2 = countNotifPendentes();
         String var4 = getApiKey((String)var1.get("nome"));
         boolean var5 = var4 != null && !var4.isEmpty();
         sendJson(
            var0,
            200,
            "{\"nome\":\""
               + escapeJson((String)var1.get("nome"))
               + "\",\"crm\":\""
               + (String)var1.get("crm")
               + "\",\"isAdmin\":"
               + (String)var1.get("isAdmin")
               + ",\"notificacoesPendentes\":"
               + var2
               + ",\"whatsappAtivo\":"
               + var5
               + ",\"trocarSenha\":"
               + "true".equals(var1.get("trocarSenha"))
               + ",\"sseClients\":"
               + sseClients.size()
               + "}"
         );
      }
   }

   // Cria o medico no banco e na lista em memoria. Devolve null se ja existir um com o mesmo nome.
   static synchronized Map<String, String> criarMedico(String nome, String telefone, String funcao, String crm, String email) throws Exception {
      String n = nome.trim();
      if (medicos.stream().anyMatch(m -> m.get("nome").equalsIgnoreCase(n))) {
         return null;
      }

      int id = medicoIdCounter.getAndIncrement();
      try (PreparedStatement ps = db.prepareStatement(
         "INSERT INTO medicos_db (id,nome,telefone,funcao,crm,email,ativo,created_at,updated_at) VALUES (?,?,?,?,?,?,1,?,?)"
      )) {
         ps.setInt(1, id);
         ps.setString(2, n);
         ps.setString(3, telefone);
         ps.setString(4, funcao);
         ps.setString(5, crm);
         ps.setString(6, email);
         ps.setString(7, now());
         ps.setString(8, now());
         ps.executeUpdate();
      }
      LinkedHashMap<String, String> m = new LinkedHashMap<>();
      m.put("id", String.valueOf(id));
      m.put("nome", n);
      m.put("telefone", telefone);
      m.put("funcao", funcao);
      m.put("crm", crm);
      m.put("email", email);
      m.put("ativo", "1");
      medicos.add(m);
      medicos.sort((a, b) -> a.get("nome").compareTo(b.get("nome")));
      saveMedicosToJson();
      addNotificacaoAndBroadcast("MEDICO_CRIADO", "Novo medico cadastrado", "Dr(a). " + n + " (" + funcao + ") foi adicionado(a) a equipe.");
      broadcast("medicos_update", "{\"acao\":\"criado\",\"id\":" + id + ",\"nome\":\"" + escapeJson(n) + "\"}");
      return m;
   }

   // POST /api/cadastro-forms  {nome, email, crm, telefone}
   // Usado pelo Apps Script do formulario de cadastro. Autentica com a chave da variavel
   // de ambiente FORMS_TOKEN (header "Authorization: Bearer <chave>"), nao com a senha do admin,
   // e so consegue cadastrar medico novo. Quem ja existe (mesmo telefone, CRM ou nome) e devolvido
   // como "existente" sem alterar nada.
   static void handleCadastroForms(HttpExchange var0) throws IOException {
      if (!"POST".equals(var0.getRequestMethod())) {
         send405(var0);
         return;
      }
      String chave = System.getenv("FORMS_TOKEN");
      if (chave == null || chave.length() < 20) {
         sendJson(var0, 503, "{\"error\":\"Cadastro pelo formulario desativado: defina FORMS_TOKEN no servidor\"}");
         return;
      }
      String token = getToken(var0);
      if (token == null || !java.security.MessageDigest.isEqual(chave.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
         sendJson(var0, 401, "{\"error\":\"Chave do formulario invalida\"}");
         return;
      }

      Map<String, String> body = parseJsonObject(new String(var0.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      String nome = body.getOrDefault("nome", "").trim().replaceAll("\\s+", " ");
      String crm = body.getOrDefault("crm", "").trim();
      String email = body.getOrDefault("email", "").trim();
      String telefone = formatarTelefone(body.getOrDefault("telefone", ""));
      if (nome.isEmpty()) {
         sendJson(var0, 400, "{\"error\":\"Nome obrigatorio\"}");
         return;
      }

      try {
         Map<String, String> m;
         synchronized (EscalaServer.class) {
            m = medicoExistente(nome, crm, telefone);
            if (m == null) {
               m = criarMedico(nome, telefone, "Plantonista", crm, email);
               if (m != null) {
                  sendJson(var0, 200, "{\"ok\":true,\"status\":\"criado\",\"id\":" + m.get("id") + ",\"nome\":\"" + escapeJson(m.get("nome")) + "\"}");
                  return;
               }
               m = medicoExistente(nome, "", "");
            }
         }
         sendJson(var0, 200, "{\"ok\":true,\"status\":\"existente\",\"id\":" + m.get("id") + ",\"nome\":\"" + escapeJson(m.get("nome")) + "\"}");
      } catch (Exception e) {
         sendJson(var0, 500, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
      }
   }

   // Mesmos 8 ultimos digitos do telefone, mesmo CRM (so numeros) ou mesmo nome sem acentos.
   static Map<String, String> medicoExistente(String nome, String crm, String telefone) {
      String fimTel = soDigitos(telefone);
      fimTel = fimTel.length() >= 8 ? fimTel.substring(fimTel.length() - 8) : "";
      String crmDig = soDigitos(crm);
      String nomeNorm = normalizarNome(nome);
      for (Map<String, String> m : medicos) {
         String t = soDigitos(m.get("telefone"));
         if (!fimTel.isEmpty() && t.endsWith(fimTel)) {
            return m;
         }
         if (!crmDig.isEmpty() && crmDig.equals(soDigitos(m.get("crm")))) {
            return m;
         }
         if (normalizarNome(m.get("nome")).equals(nomeNorm)) {
            return m;
         }
      }
      return null;
   }

   // "71 99999-8888" -> "+55 71 99999-8888" (mesmo formato dos medicos ja cadastrados)
   static String formatarTelefone(String tel) {
      String d = soDigitos(tel);
      if (d.length() == 10 || d.length() == 11) {
         d = "55" + d;
      }
      if (d.length() == 13) {
         return "+" + d.substring(0, 2) + " " + d.substring(2, 4) + " " + d.substring(4, 9) + "-" + d.substring(9);
      }
      if (d.length() == 12) {
         return "+" + d.substring(0, 2) + " " + d.substring(2, 4) + " " + d.substring(4, 8) + "-" + d.substring(8);
      }
      return tel == null ? "" : tel.trim();
   }

   static String soDigitos(String s) {
      return s == null ? "" : s.replaceAll("[^0-9]", "");
   }

   static String normalizarNome(String s) {
      if (s == null) {
         return "";
      }
      return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace("*", "").trim().replaceAll("\\s+", " ").toLowerCase();
   }

   static void handleMedicos(HttpExchange var0) throws IOException {
      String var1 = var0.getRequestMethod();
      String var2 = var0.getRequestURI().getPath();

      try {
         if ("GET".equals(var1)) {
            StringBuilder var16 = new StringBuilder("[");
            boolean var19 = true;

            for (Map var25 : medicos) {
               if (!var19) {
                  var16.append(",");
               }

               var16.append("[");
               var16.append("\"").append(escapeJson((String)var25.get("id"))).append("\",");
               var16.append("\"").append(escapeJson((String)var25.get("nome"))).append("\",");
               var16.append("\"").append(escapeJson((String)var25.get("telefone"))).append("\",");
               var16.append("\"").append(escapeJson((String)var25.get("funcao"))).append("\",");
               var16.append("\"").append(escapeJson((String)var25.get("crm"))).append("\",");
               var16.append("\"").append(escapeJson((String)var25.get("email"))).append("\"");
               var16.append("]");
               var19 = false;
            }

            var16.append("]");
            sendJson(var0, 200, var16.toString());
         } else if ("POST".equals(var1)) {
            if (!isAdmin(var0)) {
               sendJson(var0, 403, "{\"error\":\"Apenas o administrador pode criar medicos\"}");
               return;
            }

            Map<String, String> var15 = parseJsonObject(new String(var0.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String var18 = (String)var15.get("nome");
            if (var18 == null || var18.trim().isEmpty()) {
               sendJson(var0, 400, "{\"error\":\"Nome obrigatorio\"}");
               return;
            }

            Map<String, String> var12 = criarMedico(
               var18, var15.getOrDefault("telefone", ""), var15.getOrDefault("funcao", "Plantonista"), var15.getOrDefault("crm", ""), var15.getOrDefault("email", "")
            );
            if (var12 == null) {
               sendJson(var0, 400, "{\"error\":\"Medico com este nome ja existe\"}");
               return;
            }

            sendJson(var0, 200, "{\"ok\":true,\"id\":" + var12.get("id") + ",\"nome\":\"" + escapeJson(var12.get("nome")) + "\",\"funcao\":\"" + escapeJson(var12.get("funcao")) + "\"}");
         } else if ("PUT".equals(var1)) {
            if (!isAdmin(var0)) {
               sendJson(var0, 403, "{\"error\":\"Apenas o administrador pode editar medicos\"}");
               return;
            }

            String[] var3 = var2.split("/");
            String var4 = var3[var3.length - 1];
            int var5 = Integer.parseInt(var4);
            Map<String, String> var6 = parseJsonObject(new String(var0.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Map var7 = medicos.stream().filter(var1x -> var1x.get("id").equals(var4)).findFirst().orElse(null);
            if (var7 == null) {
               sendJson(var0, 404, "{\"error\":\"Medico nao encontrado\"}");
               return;
            }

            String var8 = (String)var7.get("nome");
            if (var6.containsKey("nome") && !((String)var6.get("nome")).trim().isEmpty()) {
               var7.put("nome", ((String)var6.get("nome")).trim());
            }

            if (var6.containsKey("telefone")) {
               var7.put("telefone", (String)var6.get("telefone"));
            }

            if (var6.containsKey("funcao")) {
               var7.put("funcao", (String)var6.get("funcao"));
            }

            if (var6.containsKey("crm")) {
               var7.put("crm", (String)var6.get("crm"));
            }

            if (var6.containsKey("email")) {
               var7.put("email", (String)var6.get("email"));
            }

            PreparedStatement var9 = db.prepareStatement("UPDATE medicos_db SET nome=?,telefone=?,funcao=?,crm=?,email=?,updated_at=? WHERE id=?");
            var9.setString(1, (String)var7.get("nome"));
            var9.setString(2, (String)var7.get("telefone"));
            var9.setString(3, (String)var7.get("funcao"));
            var9.setString(4, (String)var7.get("crm"));
            var9.setString(5, (String)var7.get("email"));
            var9.setString(6, now());
            var9.setInt(7, var5);
            var9.executeUpdate();
            var9.close();
            if (!var8.equals(var7.get("nome"))) {
               for (String[] var11 : escala) {
                  if (var11[5].equals(var8)) {
                     var11[5] = (String)var7.get("nome");
                  }
               }

               PreparedStatement var32 = db.prepareStatement("UPDATE escala_overrides SET plantonista=? WHERE plantonista=?");
               var32.setString(1, (String)var7.get("nome"));
               var32.setString(2, var8);
               var32.executeUpdate();
               var32.close();
            }

            medicos.sort((var0x, var1x) -> var0x.get("nome").compareTo(var1x.get("nome")));
            saveMedicosToJson();
            addNotificacaoAndBroadcast(
               "MEDICO_EDITADO",
               "Dados de medico atualizados",
               "Dr(a). " + (String)var7.get("nome") + " (" + (String)var7.get("funcao") + ") teve seus dados atualizados pelo administrador."
            );
            broadcast("medicos_update", "{\"acao\":\"editado\",\"id\":" + var5 + ",\"nome\":\"" + escapeJson((String)var7.get("nome")) + "\"}");
            sendJson(var0, 200, "{\"ok\":true,\"id\":" + var5 + ",\"nome\":\"" + escapeJson((String)var7.get("nome")) + "\"}");
         } else if ("DELETE".equals(var1)) {
            if (!isAdmin(var0)) {
               sendJson(var0, 403, "{\"error\":\"Apenas o administrador pode remover medicos\"}");
               return;
            }

            String[] var14 = var2.split("/");
            String var17 = var14[var14.length - 1];
            int var20 = Integer.parseInt(var17);
            Map var23 = medicos.stream().filter(var1x -> var1x.get("id").equals(var17)).findFirst().orElse(null);
            if (var23 == null) {
               sendJson(var0, 404, "{\"error\":\"Medico nao encontrado\"}");
               return;
            }

            String var26 = (String)var23.get("nome");
            String var28 = LocalDate.now().toString();
            long var30 = escala.stream().filter(var2x -> var2x[5].equals(var26) && var2x[1].compareTo(var28) >= 0 && "AGENDADO".equals(var2x[6])).count();
            if (var30 > 0L) {
               sendJson(
                  var0,
                  400,
                  "{\"error\":\"Nao e possivel remover "
                     + var26
                     + " pois tem "
                     + var30
                     + " plantao(oes) futuro(s) agendado(s). Reatribua os plantoes primeiro.\"}"
               );
               return;
            }

            PreparedStatement var34 = db.prepareStatement("UPDATE medicos_db SET ativo=0,updated_at=? WHERE id=?");
            var34.setString(1, now());
            var34.setInt(2, var20);
            var34.executeUpdate();
            var34.close();
            medicos.removeIf(var1x -> var1x.get("id").equals(var17));
            saveMedicosToJson();
            addNotificacaoAndBroadcast("MEDICO_REMOVIDO", "Medico removido da equipe", "Dr(a). " + var26 + " foi removido(a) da equipe pelo administrador.");
            broadcast("medicos_update", "{\"acao\":\"removido\",\"id\":" + var20 + ",\"nome\":\"" + escapeJson(var26) + "\"}");
            sendJson(var0, 200, "{\"ok\":true,\"mensagem\":\"" + escapeJson(var26) + " removido(a) da equipe.\"}");
         } else {
            send405(var0);
         }
      } catch (Exception var13) {
         sendJson(var0, 500, "{\"error\":\"" + escapeJson(var13.getMessage()) + "\"}");
         var13.printStackTrace();
      }
   }

   static void handleEscala(HttpExchange var0) throws IOException {
      String var1 = var0.getRequestMethod();
      String var2 = var0.getRequestURI().getPath();
      if ("GET".equals(var1)) {
         Map var3 = parseQuery(var0.getRequestURI().getQuery());
         String var4 = (String)var3.get("mes");
         String var5 = (String)var3.get("medico");
         String var6 = (String)var3.get("status");
         List var7 = escala.stream()
            .filter(var1x -> var4 == null || var1x[1].startsWith(var4))
            .filter(var1x -> var5 == null || var1x[5].equals(var5))
            .filter(var1x -> var6 == null || var1x[6].equals(var6))
            .collect(Collectors.toList());
         sendJson(var0, 200, arraysToJson(var7));
      } else if ("PUT".equals(var1)) {
         if (!isAdmin(var0)) {
            sendJson(var0, 403, "{\"error\":\"Apenas o administrador pode editar a escala\"}");
            return;
         }

         String[] var10 = var2.split("/");
         String var11 = var10[var10.length - 1];
         Map<String, String> var12 = parseJsonObject(new String(var0.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
         String[] var13 = escala.stream().filter(var1x -> var1x[0].equals(var11)).findFirst().orElse(null);
         if (var13 == null) {
            sendJson(var0, 404, "{\"error\":\"Plantao nao encontrado\"}");
            return;
         }

         String var14 = var13[5];
         if (var12.containsKey("plantonista")) {
            var13[5] = (String)var12.get("plantonista");
         }

         if (var12.containsKey("status")) {
            var13[6] = (String)var12.get("status");
         }

         if (var12.containsKey("turno")) {
            var13[3] = (String)var12.get("turno");
         }

         try {
            PreparedStatement var8 = db.prepareStatement(
               "INSERT OR REPLACE INTO escala_overrides (plantao_id,plantonista,status,turno,updated_at) VALUES (?,?,?,?,?)"
            );
            var8.setString(1, var11);
            var8.setString(2, var13[5]);
            var8.setString(3, var13[6]);
            var8.setString(4, var13[3]);
            var8.setString(5, now());
            var8.executeUpdate();
            var8.close();
         } catch (Exception var9) {
            System.err.println("Erro override: " + var9.getMessage());
         }

         String var15 = "Plantao " + var13[1] + " (" + var13[2] + "): " + var14 + " -> " + var13[5];
         addNotificacaoAndBroadcast("ESCALA", "Escala atualizada pelo Administrador", var15);
         broadcast("escala_update", arrayToJson(var13));
         if (!var14.equals(var13[5])) {
            notificarMedico(
               var14,
               "\ud83c\udfe5 ESCALA MEDICA\nSeu plantao foi alterado:\n\ud83d\udcc5 "
                  + var13[1]
                  + " ("
                  + var13[2]
                  + ")\n⏰ "
                  + var13[3]
                  + "\n❌ Voce foi REMOVIDO deste plantao.\nAdmin: Dr. Artur Cesar"
            );
            notificarMedico(
               var13[5],
               "\ud83c\udfe5 ESCALA MEDICA\nVoce foi ADICIONADO a um plantao:\n\ud83d\udcc5 "
                  + var13[1]
                  + " ("
                  + var13[2]
                  + ")\n⏰ "
                  + var13[3]
                  + "\n✅ Plantonista confirmado.\nAdmin: Dr. Artur Cesar"
            );
         }

         sendJson(var0, 200, arrayToJson(var13));
      } else {
         send405(var0);
      }
   }

   static void handleTrocas(HttpExchange var0) throws IOException {
      String var1 = var0.getRequestMethod();
      String var2 = var0.getRequestURI().getPath();

      try {
         if ("GET".equals(var1)) {
            String var22 = parseQuery(var0.getRequestURI().getQuery()).get("status");
            sendJson(var0, 200, objectsToJson(getTrocas(var22)));
         } else if ("POST".equals(var1)) {
            Map<String, String> var21 = parseJsonObject(new String(var0.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String var23 = (String)var21.get("solicitante");
            String var24 = (String)var21.get("plantaoId");
            String var25 = (String)var21.get("substituto");
            String var26 = (String)var21.get("motivo");
            String var27 = var21.getOrDefault("obs", "");
            if (var23 == null || var24 == null || var25 == null || var26 == null || var23.isEmpty() || var24.isEmpty() || var25.isEmpty() || var26.isEmpty()) {
               sendJson(var0, 400, "{\"error\":\"Campos obrigatorios faltando\"}");
               return;
            }

            if (var23.equals(var25)) {
               sendJson(var0, 400, "{\"error\":\"Solicitante e substituto nao podem ser o mesmo\"}");
               return;
            }

            String[] var28 = escala.stream().filter(var1x -> var1x[0].equals(var24)).findFirst().orElse(null);
            if (var28 == null) {
               sendJson(var0, 404, "{\"error\":\"Plantao nao encontrado\"}");
               return;
            }

            String var29 = "TRK-" + String.format("%04d", trocaCounter.getAndIncrement());
            PreparedStatement var30 = db.prepareStatement(
               "INSERT INTO trocas (id,plantao_id,data_plantao,dia_plantao,turno,plantonista_original,solicitante,substituto,motivo,obs,status,data_solicitacao,obs_gestor,data_decisao) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            );
            var30.setString(1, var29);
            var30.setString(2, var24);
            var30.setString(3, var28[1]);
            var30.setString(4, var28[2]);
            var30.setString(5, var28[3]);
            var30.setString(6, var28[5]);
            var30.setString(7, var23);
            var30.setString(8, var25);
            var30.setString(9, var26);
            var30.setString(10, var27);
            var30.setString(11, "PENDENTE");
            var30.setString(12, now());
            var30.setString(13, "");
            var30.setString(14, "");
            var30.executeUpdate();
            var30.close();
            addNotificacaoAndBroadcast("TROCA", "Nova solicitacao de troca", var23 + " solicita troca do plantao " + var28[1] + " para " + var25);
            broadcast(
               "troca_nova",
               "{\"id\":\""
                  + var29
                  + "\",\"solicitante\":\""
                  + escapeJson(var23)
                  + "\",\"substituto\":\""
                  + escapeJson(var25)
                  + "\",\"dataPlantao\":\""
                  + var28[1]
                  + "\"}"
            );
            notificarMedico(
               "Artur Cesar",
               "\ud83d\udd04 NOVA SOLICITACAO DE TROCA\nID: "
                  + var29
                  + "\n\ud83d\udcc5 "
                  + var28[1]
                  + " ("
                  + var28[2]
                  + ")\n⏰ "
                  + var28[3]
                  + "\n\ud83d\udc64 Solicitante: "
                  + var23
                  + "\n\ud83d\udc64 Substituto: "
                  + var25
                  + "\n\ud83d\udcac Motivo: "
                  + var26
                  + "\n\nAcesse o app para aprovar ou recusar."
            );
            LinkedHashMap var31 = new LinkedHashMap();
            var31.put("id", var29);
            var31.put("plantaoId", var24);
            var31.put("dataPlantao", var28[1]);
            var31.put("diaPlantao", var28[2]);
            var31.put("turno", var28[3]);
            var31.put("solicitante", var23);
            var31.put("substituto", var25);
            var31.put("motivo", var26);
            var31.put("obs", var27);
            var31.put("status", "PENDENTE");
            var31.put("dataSolicitacao", now());
            var31.put("obsGestor", "");
            var31.put("dataDecisao", "");
            sendJson(var0, 200, objectToJson(var31));
         } else if ("PUT".equals(var1)) {
            if (!isAdmin(var0)) {
               sendJson(var0, 403, "{\"error\":\"Apenas o administrador pode processar trocas\"}");
               return;
            }

            String[] var3 = var2.split("/");
            String var4 = var3[var3.length - 1];
            Map<String, String> var5 = parseJsonObject(new String(var0.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String var6 = (String)var5.get("acao");
            String var7 = var5.getOrDefault("obsGestor", "");
            ResultSet var8 = db.prepareStatement("SELECT * FROM trocas WHERE id='" + var4 + "'").executeQuery();
            if (!var8.next()) {
               sendJson(var0, 404, "{\"error\":\"Troca nao encontrada\"}");
               var8.close();
               return;
            }

            if (!"PENDENTE".equals(var8.getString("status"))) {
               sendJson(var0, 400, "{\"error\":\"Troca ja processada\"}");
               var8.close();
               return;
            }

            String var9 = var8.getString("plantao_id");
            String var10 = var8.getString("substituto");
            String var11 = var8.getString("solicitante");
            String var12 = var8.getString("data_plantao");
            String var13 = var8.getString("turno");
            String var14 = var8.getString("dia_plantao");
            var8.close();
            String var15 = now();
            if (!"aprovar".equals(var6)) {
               if (!"recusar".equals(var6)) {
                  sendJson(var0, 400, "{\"error\":\"Acao invalida\"}");
                  return;
               }

               if (var7.isEmpty()) {
                  sendJson(var0, 400, "{\"error\":\"Motivo da recusa obrigatorio\"}");
                  return;
               }

               PreparedStatement var32 = db.prepareStatement("UPDATE trocas SET status='RECUSADA',obs_gestor=?,data_decisao=? WHERE id=?");
               var32.setString(1, var7);
               var32.setString(2, var15);
               var32.setString(3, var4);
               var32.executeUpdate();
               var32.close();
               addNotificacaoAndBroadcast("TROCA_RECUSADA", "Troca RECUSADA", "Troca " + var4 + " em " + var12 + " foi RECUSADA. Motivo: " + var7);
               broadcast("troca_recusada", "{\"id\":\"" + var4 + "\",\"motivo\":\"" + escapeJson(var7) + "\"}");
               notificarMedico(
                  var11,
                  "❌ TROCA RECUSADA - "
                     + var4
                     + "\n\ud83d\udcc5 "
                     + var12
                     + " ("
                     + var14
                     + ")\n⏰ "
                     + var13
                     + "\n\nSua solicitacao foi RECUSADA.\nMotivo: "
                     + var7
                     + "\n\nVoce permanece escalado neste plantao."
               );
            } else {
               String var16 = var7.isEmpty() ? "Aprovado pelo Dr. Artur Cesar." : var7;
               PreparedStatement var17 = db.prepareStatement("UPDATE trocas SET status='APROVADA',obs_gestor=?,data_decisao=? WHERE id=?");
               var17.setString(1, var16);
               var17.setString(2, var15);
               var17.setString(3, var4);
               var17.executeUpdate();
               var17.close();

               for (String[] var19 : escala) {
                  if (var19[0].equals(var9)) {
                     var19[5] = var10;
                     break;
                  }
               }

               PreparedStatement var34 = db.prepareStatement(
                  "INSERT OR REPLACE INTO escala_overrides (plantao_id,plantonista,status,turno,updated_at) VALUES (?,?,?,?,?)"
               );
               String[] var35 = escala.stream().filter(var1x -> var1x[0].equals(var9)).findFirst().orElse(new String[]{"", "", "", "", "", "", ""});
               var34.setString(1, var9);
               var34.setString(2, var10);
               var34.setString(3, var35[6]);
               var34.setString(4, var35[3]);
               var34.setString(5, var15);
               var34.executeUpdate();
               var34.close();
               addNotificacaoAndBroadcast(
                  "TROCA_APROVADA", "Troca APROVADA", "Troca " + var4 + ": " + var11 + " -> " + var10 + " em " + var12 + " foi APROVADA."
               );
               broadcast(
                  "troca_aprovada",
                  "{\"id\":\""
                     + var4
                     + "\",\"plantaoId\":\""
                     + var9
                     + "\",\"novoPlantonistaId\":\""
                     + escapeJson(var10)
                     + "\",\"dataPlantao\":\""
                     + var12
                     + "\"}"
               );
               notificarMedico(
                  var11,
                  "✅ TROCA APROVADA - "
                     + var4
                     + "\n\ud83d\udcc5 "
                     + var12
                     + " ("
                     + var14
                     + ")\n⏰ "
                     + var13
                     + "\n\nSua solicitacao foi APROVADA pelo Dr. Artur Cesar.\nVoce esta DISPENSADO deste plantao."
               );
               notificarMedico(
                  var10,
                  "✅ TROCA APROVADA - "
                     + var4
                     + "\n\ud83d\udcc5 "
                     + var12
                     + " ("
                     + var14
                     + ")\n⏰ "
                     + var13
                     + "\n\nVoce foi CONFIRMADO como plantonista.\nSolicitante original: "
                     + var11
               );
            }

            List var33 = getTrocas(null).stream().filter(var1x -> var1x.get("id").equals(var4)).collect(Collectors.toList());
            sendJson(var0, 200, var33.isEmpty() ? "{}" : objectToJson((Map<String, String>)var33.get(0)));
         } else {
            send405(var0);
         }
      } catch (Exception var20) {
         sendJson(var0, 500, "{\"error\":\"" + escapeJson(var20.getMessage()) + "\"}");
         var20.printStackTrace();
      }
   }

   static void handleCheckin(HttpExchange var0) throws IOException {
      try {
         if ("GET".equals(var0.getRequestMethod())) {
            String var17 = parseQuery(var0.getRequestURI().getQuery()).get("medico");
            String var18 = "SELECT * FROM checkins" + (var17 != null && !var17.isEmpty() ? " WHERE medico=?" : "") + " ORDER BY data DESC LIMIT 100";
            PreparedStatement var19 = db.prepareStatement(var18);
            if (var17 != null && !var17.isEmpty()) {
               var19.setString(1, var17);
            }

            ResultSet var20 = var19.executeQuery();
            ArrayList var21 = new ArrayList();

            while (var20.next()) {
               LinkedHashMap var22 = new LinkedHashMap();
               var22.put("id", var20.getString("id"));
               var22.put("tipo", var20.getString("tipo"));
               var22.put("medico", var20.getString("medico"));
               var22.put("plantaoId", var20.getString("plantao_id"));
               var22.put("lat", var20.getString("lat"));
               var22.put("lon", var20.getString("lon"));
               var22.put("distanciaKm", var20.getString("distancia_km"));
               var22.put("data", var20.getString("data"));
               var21.add(var22);
            }

            var20.close();
            var19.close();
            sendJson(var0, 200, objectsToJson(var21));
            return;
         }

         if (!"POST".equals(var0.getRequestMethod())) {
            send405(var0);
            return;
         }

         Map<String, String> var1 = parseJsonObject(new String(var0.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
         String var2 = (String)var1.get("lat");
         String var3 = (String)var1.get("lon");
         String var4 = var1.getOrDefault("tipo", "checkin");
         String var5 = var1.getOrDefault("medico", "");
         String var6 = var1.getOrDefault("plantaoId", "");
         if (var2 == null || var3 == null) {
            sendJson(var0, 400, "{\"error\":\"Coordenadas GPS obrigatorias\"}");
            return;
         }

         double var7 = Double.parseDouble(var2);
         double var9 = Double.parseDouble(var3);
         double var11 = haversineKm(var7, var9, -12.7010338, -38.3324179);
         if (var11 > 2.0) {
            sendJson(
               var0,
               400,
               "{\"error\":\"Voce esta a "
                  + String.format("%.1f", var11)
                  + "km do hospital. O check-in requer estar dentro de 2.0km.\",\"distancia\":"
                  + String.format("%.2f", var11)
                  + "}"
            );
            return;
         }

         String var13 = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
         PreparedStatement var14 = db.prepareStatement("INSERT INTO checkins (id,tipo,medico,plantao_id,lat,lon,distancia_km,data) VALUES (?,?,?,?,?,?,?,?)");
         var14.setString(1, var13);
         var14.setString(2, var4);
         var14.setString(3, var5);
         var14.setString(4, var6);
         var14.setString(5, var2);
         var14.setString(6, var3);
         var14.setString(7, String.format("%.2f", var11));
         var14.setString(8, now());
         var14.executeUpdate();
         var14.close();
         String var15 = "checkin".equals(var4) ? "CHECK-IN" : "CHECK-OUT";
         addNotificacaoAndBroadcast(
            "CHECKIN", var15 + " registrado", var5 + " realizou " + var15 + " em " + now() + " (" + String.format("%.0f", var11 * 1000.0) + "m do hospital)"
         );
         broadcast("checkin", "{\"medico\":\"" + escapeJson(var5) + "\",\"tipo\":\"" + var4 + "\",\"distanciaKm\":" + String.format("%.2f", var11) + "}");
         sendJson(
            var0,
            200,
            "{\"ok\":true,\"tipo\":\"" + var4 + "\",\"distanciaKm\":" + String.format("%.2f", var11) + ",\"dentroDoRaio\":true,\"data\":\"" + now() + "\"}"
         );
      } catch (Exception var16) {
         sendJson(var0, 500, "{\"error\":\"" + escapeJson(var16.getMessage()) + "\"}");
         var16.printStackTrace();
      }
   }

   static void handleNotificacoes(HttpExchange var0) throws IOException {
      try {
         if ("GET".equals(var0.getRequestMethod())) {
            ResultSet var1 = db.createStatement().executeQuery("SELECT * FROM notificacoes ORDER BY id DESC LIMIT 100");
            ArrayList var2 = new ArrayList();

            while (var1.next()) {
               LinkedHashMap var3 = new LinkedHashMap();
               var3.put("id", String.valueOf(var1.getInt("id")));
               var3.put("tipo", var1.getString("tipo"));
               var3.put("titulo", var1.getString("titulo"));
               var3.put("mensagem", var1.getString("mensagem"));
               var3.put("data", var1.getString("data"));
               var3.put("lida", var1.getInt("lida") == 1 ? "true" : "false");
               var2.add(var3);
            }

            var1.close();
            sendJson(var0, 200, objectsToJson(var2));
         } else if ("PUT".equals(var0.getRequestMethod())) {
            db.createStatement().execute("UPDATE notificacoes SET lida=1");
            sendJson(var0, 200, "{\"ok\":true}");
         } else {
            send405(var0);
         }
      } catch (Exception var4) {
         sendJson(var0, 500, "{\"error\":\"" + escapeJson(var4.getMessage()) + "\"}");
         var4.printStackTrace();
      }
   }

   static void handleWhatsApp(HttpExchange var0) throws IOException {
      String var1 = var0.getRequestMethod();

      try {
         if ("GET".equals(var1)) {
            if (!isAdmin(var0)) {
               sendJson(var0, 403, "{\"error\":\"Apenas admin\"}");
               return;
            }

            ResultSet var10 = db.createStatement().executeQuery("SELECT medico,telefone,apikey,ativo,created_at FROM whatsapp_keys ORDER BY medico");
            ArrayList var11 = new ArrayList();

            while (var10.next()) {
               LinkedHashMap var12 = new LinkedHashMap();
               var12.put("medico", var10.getString("medico"));
               var12.put("telefone", var10.getString("telefone"));
               var12.put(
                  "apikey",
                  var10.getString("apikey") != null ? "****" + var10.getString("apikey").substring(Math.max(0, var10.getString("apikey").length() - 4)) : ""
               );
               var12.put("ativo", String.valueOf(var10.getInt("ativo")));
               var12.put("createdAt", var10.getString("created_at"));
               var11.add(var12);
            }

            var10.close();
            sendJson(var0, 200, objectsToJson(var11));
         } else if ("POST".equals(var1)) {
            Map var9 = getSession(var0);
            if (var9 == null) {
               sendJson(var0, 401, "{\"error\":\"Nao autenticado\"}");
               return;
            }

            Map<String, String> var3 = parseJsonObject(new String(var0.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String var4 = (String)var3.get("apikey");
            String var5 = (String)var3.getOrDefault("medico", (String)var9.get("nome"));
            if (!((String)var9.get("nome")).equals(var5) && !Boolean.parseBoolean((String)var9.get("isAdmin"))) {
               sendJson(var0, 403, "{\"error\":\"Sem permissao\"}");
               return;
            }

            if (var4 == null || var4.trim().isEmpty()) {
               sendJson(var0, 400, "{\"error\":\"apikey obrigatoria\"}");
               return;
            }

            String var6 = getTelefone(var5);
            PreparedStatement var7 = db.prepareStatement("INSERT OR REPLACE INTO whatsapp_keys (medico,telefone,apikey,ativo,created_at) VALUES (?,?,?,1,?)");
            var7.setString(1, var5);
            var7.setString(2, var6 != null ? var6 : "");
            var7.setString(3, var4.trim());
            var7.setString(4, now());
            var7.executeUpdate();
            var7.close();
            sendWhatsApp(
               var6,
               var4.trim(),
               "✅ Escala Medica - Hospital Santa Helena\nWhatsApp configurado com sucesso para " + var5 + "!\nVoce recebera notificacoes de trocas de plantao."
            );
            sendJson(var0, 200, "{\"ok\":true,\"mensagem\":\"WhatsApp configurado! Mensagem de teste enviada.\"}");
         } else if ("DELETE".equals(var1)) {
            Map var2 = getSession(var0);
            if (var2 == null) {
               sendJson(var0, 401, "{\"error\":\"Nao autenticado\"}");
               return;
            }

            db.prepareStatement("DELETE FROM whatsapp_keys WHERE medico='" + (String)var2.get("nome") + "'").executeUpdate();
            sendJson(var0, 200, "{\"ok\":true}");
         } else {
            send405(var0);
         }
      } catch (Exception var8) {
         sendJson(var0, 500, "{\"error\":\"" + escapeJson(var8.getMessage()) + "\"}");
         var8.printStackTrace();
      }
   }

   static void handleStats(HttpExchange var0) throws IOException {
      try {
         if (!"GET".equals(var0.getRequestMethod())) {
            send405(var0);
            return;
         }

         String var1 = LocalDate.now().toString();
         String var2 = var1.substring(0, 7);
         long var3 = escala.stream().filter(var0x -> "AGENDADO".equals(var0x[6])).count();
         long var5 = escala.stream().filter(var0x -> "A DEFINIR".equals(var0x[6])).count();
         long var7 = escala.stream().filter(var1x -> var1x[1].startsWith(var2) && "AGENDADO".equals(var1x[6])).count();
         ResultSet var9 = db.createStatement().executeQuery("SELECT COUNT(*) FROM trocas WHERE status='PENDENTE'");
         long var10 = var9.next() ? var9.getLong(1) : 0L;
         var9.close();
         ResultSet var12 = db.createStatement().executeQuery("SELECT COUNT(*) FROM notificacoes WHERE lida=0");
         long var13 = var12.next() ? var12.getLong(1) : 0L;
         var12.close();
         ResultSet var15 = db.createStatement().executeQuery("SELECT COUNT(*) FROM checkins");
         long var16 = var15.next() ? var15.getLong(1) : 0L;
         var15.close();
         ResultSet var18 = db.createStatement().executeQuery("SELECT COUNT(*) FROM whatsapp_keys WHERE ativo=1");
         long var19 = var18.next() ? var18.getLong(1) : 0L;
         var18.close();
         String[] var21 = escala.stream().filter(var1x -> var1x[1].equals(var1)).findFirst().orElse(null);
         StringBuilder var22 = new StringBuilder("{");
         var22.append("\"total\":").append(escala.size()).append(",");
         var22.append("\"agendados\":").append(var3).append(",");
         var22.append("\"aDefinir\":").append(var5).append(",");
         var22.append("\"hoje\":").append(var21 != null ? arrayToJson(var21) : "null").append(",");
         var22.append("\"mesCount\":").append(var7).append(",");
         var22.append("\"trocasPendentes\":").append(var10).append(",");
         var22.append("\"notificacoesPendentes\":").append(var13).append(",");
         var22.append("\"totalCheckins\":").append(var16).append(",");
         var22.append("\"whatsappAtivos\":").append(var19).append(",");
         var22.append("\"sseClients\":").append(sseClients.size()).append(",");
         var22.append("\"medicos\":").append(medicos.size()).append(",");
         var22.append("\"hospitalLat\":").append(-12.7010338).append(",");
         var22.append("\"hospitalLon\":").append(-38.3324179).append(",");
         var22.append("\"checkinRaioKm\":").append(2.0);
         var22.append("}");
         sendJson(var0, 200, var22.toString());
      } catch (Exception var23) {
         sendJson(var0, 500, "{\"error\":\"" + escapeJson(var23.getMessage()) + "\"}");
         var23.printStackTrace();
      }
   }

   static long countNotifPendentes() {
      try {
         ResultSet var0 = db.createStatement().executeQuery("SELECT COUNT(*) FROM notificacoes WHERE lida=0");
         long var1 = var0.next() ? var0.getLong(1) : 0L;
         var0.close();
         return var1;
      } catch (Exception var3) {
         return 0L;
      }
   }

   static List<Map<String, String>> getTrocas(String var0) throws Exception {
      String var1 = "SELECT * FROM trocas" + (var0 != null && !var0.isEmpty() ? " WHERE status=?" : "") + " ORDER BY data_solicitacao DESC";
      PreparedStatement var2 = db.prepareStatement(var1);
      if (var0 != null && !var0.isEmpty()) {
         var2.setString(1, var0);
      }

      ResultSet var3 = var2.executeQuery();
      ArrayList var4 = new ArrayList();

      while (var3.next()) {
         LinkedHashMap var5 = new LinkedHashMap();
         var5.put("id", var3.getString("id"));
         var5.put("plantaoId", var3.getString("plantao_id"));
         var5.put("dataPlantao", var3.getString("data_plantao"));
         var5.put("diaPlantao", var3.getString("dia_plantao"));
         var5.put("turno", var3.getString("turno"));
         var5.put("plantonistaOriginal", var3.getString("plantonista_original"));
         var5.put("solicitante", var3.getString("solicitante"));
         var5.put("substituto", var3.getString("substituto"));
         var5.put("motivo", var3.getString("motivo"));
         var5.put("obs", var3.getString("obs"));
         var5.put("status", var3.getString("status"));
         var5.put("dataSolicitacao", var3.getString("data_solicitacao"));
         var5.put("obsGestor", var3.getString("obs_gestor"));
         var5.put("dataDecisao", var3.getString("data_decisao"));
         var4.add(var5);
      }

      var3.close();
      var2.close();
      return var4;
   }

   static double haversineKm(double var0, double var2, double var4, double var6) {
      double var8 = 6371.0;
      double var10 = Math.toRadians(var4 - var0);
      double var12 = Math.toRadians(var6 - var2);
      double var14 = Math.sin(var10 / 2.0) * Math.sin(var10 / 2.0)
         + Math.cos(Math.toRadians(var0)) * Math.cos(Math.toRadians(var4)) * Math.sin(var12 / 2.0) * Math.sin(var12 / 2.0);
      return var8 * 2.0 * Math.atan2(Math.sqrt(var14), Math.sqrt(1.0 - var14));
   }

   static void sendJson(HttpExchange var0, int var1, String var2) throws IOException {
      byte[] var3 = var2.getBytes(StandardCharsets.UTF_8);
      var0.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
      var0.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
      var0.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type");
      var0.sendResponseHeaders(var1, (long)var3.length);
      var0.getResponseBody().write(var3);
      var0.getResponseBody().close();
   }

   static void send405(HttpExchange var0) throws IOException {
      var0.sendResponseHeaders(405, -1L);
      var0.getResponseBody().close();
   }

   static Map<String, String> parseQuery(String var0) {
      HashMap var1 = new HashMap();
      if (var0 == null) {
         return var1;
      } else {
         for (String var5 : var0.split("&")) {
            String[] var6 = var5.split("=", 2);
            if (var6.length == 2) {
               var1.put(var6[0], URLDecoder.decode(var6[1], StandardCharsets.UTF_8));
            }
         }

         return var1;
      }
   }

   static String now() {
      return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
   }

   static String escapeJson(String var0) {
      return var0 == null ? "" : var0.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
   }

   static String arrayToJson(String[] var0) {
      StringBuilder var1 = new StringBuilder("[");

      for (int var2 = 0; var2 < var0.length; var2++) {
         if (var2 > 0) {
            var1.append(",");
         }

         var1.append("\"").append(escapeJson(var0[var2])).append("\"");
      }

      return var1.append("]").toString();
   }

   static String arraysToJson(List<String[]> var0) {
      return "[" + var0.stream().map(EscalaServer::arrayToJson).collect(Collectors.joining(",")) + "]";
   }

   static String objectToJson(Map<String, String> var0) {
      StringBuilder var1 = new StringBuilder("{");
      boolean var2 = true;

      for (Entry var4 : var0.entrySet()) {
         if (!var2) {
            var1.append(",");
         }

         var1.append("\"").append(escapeJson((String)var4.getKey())).append("\":\"").append(escapeJson((String)var4.getValue())).append("\"");
         var2 = false;
      }

      return var1.append("}").toString();
   }

   static String objectsToJson(List<Map<String, String>> var0) {
      return "[" + var0.stream().map(EscalaServer::objectToJson).collect(Collectors.joining(",")) + "]";
   }

   static List<String[]> parseJsonArrayOfArrays(String var0) {
      ArrayList var1 = new ArrayList();
      var0 = var0.trim();
      if (!var0.startsWith("[")) {
         return var1;
      } else {
         int var2 = 1;

         while (var2 < var0.length()) {
            int var3 = var0.indexOf(91, var2);
            if (var3 < 0) {
               break;
            }

            int var4 = 0;
            int var5 = -1;

            for (int var6 = var3; var6 < var0.length(); var6++) {
               char var7 = var0.charAt(var6);
               if (var7 == '[') {
                  var4++;
               } else if (var7 == ']') {
                  if (--var4 == 0) {
                     var5 = var6;
                     break;
                  }
               }
            }

            if (var5 <= var3) {
               break;
            }

            String[] var9 = parseStringArray(var0.substring(var3 + 1, var5));
            if (var9.length > 0) {
               var1.add(var9);
            }

            var2 = var5 + 1;
         }

         return var1;
      }
   }

   static String[] parseStringArray(String var0) {
      ArrayList<String> var1 = new ArrayList<>();
      int var2 = 0;

      while (var2 < var0.length()) {
         if (var0.charAt(var2) == '"') {
            StringBuilder var6 = new StringBuilder();
            var2++;

            while (var2 < var0.length()) {
               char var4 = var0.charAt(var2);
               if (var4 == '\\' && var2 + 1 < var0.length()) {
                  char var5 = var0.charAt(var2 + 1);
                  if (var5 == '"') {
                     var6.append('"');
                  } else if (var5 == '\\') {
                     var6.append('\\');
                  } else if (var5 == 'n') {
                     var6.append('\n');
                  } else {
                     var6.append(var5);
                  }

                  var2 += 2;
               } else {
                  if (var4 == '"') {
                     var2++;
                     break;
                  }

                  var6.append(var4);
                  var2++;
               }
            }

            var1.add(var6.toString());
         } else if ((var0.charAt(var2) < '0' || var0.charAt(var2) > '9') && var0.charAt(var2) != '-') {
            var2++;
         } else {
            int var3 = var2;

            while (var2 < var0.length() && var0.charAt(var2) != ',' && var0.charAt(var2) != ']') {
               var2++;
            }

            var1.add(var0.substring(var3, var2).trim());
         }
      }

      return var1.toArray(new String[0]);
   }

   static Map<String, String> parseJsonObject(String var0) {
      LinkedHashMap var1 = new LinkedHashMap();
      var0 = var0.trim();
      if (!var0.startsWith("{")) {
         return var1;
      } else {
         var0 = var0.substring(1, var0.lastIndexOf(125));
         int var2 = 0;

         while (var2 < var0.length()) {
            int var3 = var0.indexOf(34, var2);
            if (var3 < 0) {
               break;
            }

            int var4 = var0.indexOf(34, var3 + 1);
            if (var4 < 0) {
               break;
            }

            String var5 = var0.substring(var3 + 1, var4);
            int var6 = var0.indexOf(58, var4);
            if (var6 < 0) {
               break;
            }

            int var7 = var6 + 1;

            while (var7 < var0.length() && var0.charAt(var7) == ' ') {
               var7++;
            }

            String var8;
            int var9;
            if (var7 < var0.length() && var0.charAt(var7) == '"') {
               StringBuilder var16 = new StringBuilder();
               int var11 = var7 + 1;

               while (var11 < var0.length()) {
                  char var12 = var0.charAt(var11);
                  if (var12 == '\\' && var11 + 1 < var0.length()) {
                     char var13 = var0.charAt(var11 + 1);
                     if (var13 == '"') {
                        var16.append('"');
                     } else if (var13 == '\\') {
                        var16.append('\\');
                     } else if (var13 == 'n') {
                        var16.append('\n');
                     } else {
                        var16.append(var13);
                     }

                     var11 += 2;
                  } else {
                     if (var12 == '"') {
                        var11++;
                        break;
                     }

                     var16.append(var12);
                     var11++;
                  }
               }

               var8 = var16.toString();
               var9 = var11;
            } else {
               int var10 = var7;

               while (var10 < var0.length() && var0.charAt(var10) != ',' && var0.charAt(var10) != '}') {
                  var10++;
               }

               var8 = var0.substring(var7, var10).trim();
               var9 = var10;
            }

            var1.put(var5, var8);
            var2 = var9;
         }

         return var1;
      }
   }
}
