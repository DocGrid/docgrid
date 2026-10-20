import java.sql.*;import java.nio.file.*;import java.util.*;import java.security.*;import java.nio.charset.StandardCharsets;
import org.postgresql.PGConnection;import org.postgresql.PGStatement;

/** 기존 50,008청크 캐시·스키마에서 JDBC 자연 계획 전환을 검사한다. HTTP 지연은 다루지 않는다. */
public final class Stage50 {
 static final int DOCS=3559,ROWS=50008,DIM=1024,REPEATS=30;
 static String run=System.getenv("GP_RUN");
 static String esc(String x){return x.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");}
 static String json(Object x){if(x==null)return "null";if(x instanceof Number||x instanceof Boolean)return x.toString();if(x instanceof Map<?,?> m){List<String>a=new ArrayList<>();m.forEach((k,v)->a.add(json(k.toString())+":"+json(v)));return "{"+String.join(",",a)+"}";}if(x instanceof Collection<?> c){List<String>a=new ArrayList<>();for(Object v:c)a.add(json(v));return "["+String.join(",",a)+"]";}return "\""+esc(x.toString())+"\"";}
 static void emit(String event,Object...pairs){Map<String,Object>m=new LinkedHashMap<>();m.put("실행_ID",run);m.put("이벤트",event);for(int i=0;i<pairs.length;i+=2)m.put(pairs[i].toString(),pairs[i+1]);System.out.println(json(m));System.out.flush();}
 static String hash(String s)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}
 static Connection connection(String url)throws Exception{Properties p=new Properties();p.setProperty("user",System.getenv("OPENSQL_APP_USER"));p.setProperty("password",System.getenv("OPENSQL_APP_PASSWORD"));p.setProperty("ApplicationName","docgrid-gp-stage0-20261021");p.setProperty("connectTimeout","5");p.setProperty("socketTimeout","30");Connection c=DriverManager.getConnection(url,p);try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT current_database()")){r.next();if(!r.getString(1).equals(System.getenv("GP_DBNAME")))throw new IllegalStateException("isolated_database_mismatch");}try(Statement schema=c.createStatement()){schema.execute("SET search_path=docgrid_permission_recall_545,public");}return c;}
 static void seed()throws Exception{try(Connection c=connection(System.getenv("GP_DIRECT_URL"))){com.opensource.docgrid.domain.search.benchmark.FullFixture.seed(c);}}
 static String vector()throws Exception{return com.opensource.docgrid.domain.search.benchmark.FullFixture.questionVector();}
 static List<String> parameterOrder(String source){List<String>names=new ArrayList<>();java.util.regex.Matcher m=java.util.regex.Pattern.compile(":(queryVector|modelId|permittedIds|topK)").matcher(source);while(m.find()){String n=m.group(1);if(n.equals("permittedIds")){for(int i=1;i<=DOCS;i++)names.add("doc"+i);}else names.add(n);}return names;}
 static String jdbcSql(String source){String ids=String.join(",",Collections.nCopies(DOCS,"?"));return source.replace(":queryVector","?").replace(":modelId","?").replace(":permittedIds",ids).replace(":topK","?");}
 static void bind(PreparedStatement p,List<String>params,String vector)throws SQLException{int i=1;for(String n:params){if(n.equals("queryVector"))p.setString(i,vector);else if(n.equals("modelId"))p.setLong(i,1);else if(n.equals("topK"))p.setInt(i,40);else p.setLong(i,Long.parseLong(n.substring(3)));i++;}}
 static String backend(Connection c)throws SQLException{try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT md5(inet_server_addr()::text||':'||pg_backend_pid()::text),pg_is_in_recovery(),current_setting('plan_cache_mode')")){r.next();if(!r.getString(3).equals("auto"))throw new SQLException("unexpected_plan_mode");return r.getString(1)+":"+(r.getBoolean(2)?"standby":"primary");}}
 static Map<String,Object> counters(Connection c,String marker)throws Exception{
  Map<String,Object>m=new LinkedHashMap<>();long g=0,k=0,n=0,maxG=0,maxK=0;String selected=null;long best=-1;
  List<Map<String,Object>> statements=new ArrayList<>();
  // 카운터 조회 SQL 자체도 Proxy가 캐시하므로 pg_prepared_statements를 포함하는 점검문은 반드시 제외한다.
  String query="SELECT name,generic_plans,custom_plans,parameter_types::text FROM pg_prepared_statements WHERE position('"+marker+"' in statement)>0 AND position('pg_prepared_statements' in statement)=0 AND NOT from_sql";
  try(Statement s=c.createStatement();ResultSet r=s.executeQuery(query)){
   while(r.next()){
    n++;long gg=r.getLong(2),kk=r.getLong(3);g+=gg;k+=kk;maxG=Math.max(maxG,gg);maxK=Math.max(maxK,kk);
    Map<String,Object>one=new LinkedHashMap<>();one.put("준비문_별칭",hash(r.getString(1)).substring(0,16));one.put("generic_plans",gg);one.put("custom_plans",kk);one.put("매개변수수",r.getString(4).split(",").length);one.put("벡터바인딩타입","varchar");statements.add(one);
    long score=gg*1000000+kk;if(score>best){best=score;selected=r.getString(1);}
   }
  }
  m.put("generic_plans",g);m.put("custom_plans",k);m.put("관측_준비문수",n);m.put("단일준비문_generic_최대",maxG);m.put("단일준비문_custom_최대",maxK);m.put("준비문별",statements);m.put("name",selected);return m;
 }
 static void explain(Connection c,String marker,List<String>params,String vector,String variant)throws Exception{
  c.setReadOnly(true);c.setAutoCommit(false);try(Statement s=c.createStatement()){
   s.execute("SET LOCAL statement_timeout='30s'");s.execute("SET LOCAL search_path=docgrid_permission_recall_545,public");String node=backend(c);Map<String,Object>m=counters(c,marker);String name=(String)m.get("name");
   if(name==null){emit("실행계획 미관측","SQL",variant,"이유","해당 backend에 이름 있는 준비문 없음");c.rollback();return;}
   List<String>values=new ArrayList<>();for(String p:params)values.add(p.equals("queryVector")?"'"+vector+"'":p.equals("modelId")?"1":p.equals("topK")?"40":p.substring(3));
   List<String>plan=new ArrayList<>();String query="EXPLAIN (ANALYZE,BUFFERS,SUMMARY) EXECUTE \""+name.replace("\"","\"\"")+"\"("+String.join(",",values)+")";
   try(ResultSet r=s.executeQuery(query)){while(r.next()){String line=r.getString(1).replaceAll("'\\[[^\\]]+\\]'(::vector)?","'<D01 BGE-M3 1024차원 벡터>'");line=line.replaceAll("\'\\{[0-9,]+\\}\'(::bigint\\[\\])?","\'<허용 문서 ID 목록>\'");line=line.replaceAll("ARRAY\\[[^\\]]*\\]","ARRAY[<매개변수 목록>]");plan.add(line);}}
   boolean correct=plan.stream().anyMatch(line->line.toLowerCase().contains("embeddings"));emit(correct?"별도 EXPLAIN ANALYZE":"실행계획 출처 불일치","SQL",variant,"backend_별칭",node,"계획",plan,"검색계획확인",correct,"측정표본포함",false);c.commit();
  }catch(SQLException e){c.rollback();emit("실행계획 관측 오류","SQL",variant,"SQLState",e.getSQLState());}
 }
 static void measure()throws Exception{
  String fixed=Files.readString(Path.of(System.getenv("GP_SQL_FILE")));String before=fixed.replace("(SELECT CAST(:queryVector AS vector))","CAST(:queryVector AS vector)");if(fixed.equals(before))throw new IllegalStateException("fixed_sql_required");
  String vector=vector();List<String>params=parameterOrder(fixed);
  try(Connection c=connection(System.getenv("GP_JDBC_URL"))){emit("측정 조건","계측버전","GP50-R4","JDBC",c.getMetaData().getDriverVersion(),"prepareThreshold",c.unwrap(PGConnection.class).getPrepareThreshold(),"plan_cache_mode","auto","반복_각SQL",REPEATS,"허용문서",DOCS,"SQL후보상한",40,"SQL_SHA256",hash(fixed));
   c.setReadOnly(true);c.setAutoCommit(false);try(Statement s=c.createStatement()){s.execute("SET LOCAL statement_timeout='30s'");s.execute("SET LOCAL search_path=docgrid_permission_recall_545,public");try(ResultSet r=s.executeQuery("SELECT count(*),min(vector_dims(vector)),(SELECT count(*) FROM documents) FROM embeddings")){r.next();if(r.getLong(1)!=ROWS||r.getInt(2)!=DIM||r.getLong(3)!=DOCS)throw new IllegalStateException("fixture_not_ready");emit("적재 건수 확인","청크수",r.getLong(1),"차원",r.getInt(2),"문서수",r.getLong(3));}Map<String,String>settings=new LinkedHashMap<>();for(String key:List.of("server_version","maintenance_work_mem","work_mem","shared_buffers","effective_cache_size","random_page_cost","max_parallel_workers_per_gather","default_statistics_target","jit","hnsw.ef_search","hnsw.iterative_scan")){try(ResultSet r=s.executeQuery("SHOW "+key)){r.next();settings.put(key,r.getString(1));}}emit("실제 세션 설정","설정",settings);c.commit();}
   String beforeMarker="GP_STAGE0_"+run.replace('-','_')+"_before",afterMarker="GP_STAGE0_"+run.replace('-','_')+"_after";
   try(PreparedStatement a=c.prepareStatement("/* "+beforeMarker+" */ "+jdbcSql(before));PreparedStatement b=c.prepareStatement("/* "+afterMarker+" */ "+jdbcSql(fixed))){
    String expected=null;
    // 3. 같은 JDBC 연결·두 PreparedStatement를 유지하고, 각 호출은 앱과 같은 read-only 트랜잭션으로 커밋한다.
    for(int i=1;i<=REPEATS;i++)for(int v=0;v<2;v++){
     PreparedStatement p=v==0?a:b;String variant=v==0?"수정 전":"수정 후",marker=v==0?beforeMarker:afterMarker;
     try(Statement s=c.createStatement()){s.execute("SET LOCAL statement_timeout='30s'");s.execute("SET LOCAL search_path=docgrid_permission_recall_545,public");String first=backend(c);bind(p,params,vector);long start=System.nanoTime();List<String>rows=new ArrayList<>();
      try(ResultSet r=p.executeQuery()){while(r.next())rows.add(r.getLong("embedding_id")+":"+r.getLong("chunk_id")+":"+r.getDouble("distance"));}
      double elapsed=(System.nanoTime()-start)/1e6;String last=backend(c);if(!first.equals(last))throw new IllegalStateException("backend_changed_within_transaction");
      String resultHash=hash(String.join("\n",rows));if(expected==null)expected=resultHash;boolean same=expected.equals(resultHash);Map<String,Object>counter=counters(c,marker);counter.remove("name");
      emit("SQL 반복 관측","SQL",variant,"회차",i,"JDBC_왕복_ms",elapsed,"결과수",rows.size(),"결과동일",same,"결과_SHA256",resultHash,"backend_별칭",last,"isUseServerPrepare_호출후_flag",p.unwrap(PGStatement.class).isUseServerPrepare(),"계획카운터",counter);c.commit();if(!same)throw new IllegalStateException("result_mismatch");
     }catch(Exception e){c.rollback();throw e;}
    }
    // 4. 서버 실행 시간은 같은 트랜잭션 backend의 실제 준비문으로 별도 관측하고 반복 표본에 합산하지 않는다.
    explain(c,beforeMarker,params,vector,"수정 전");explain(c,afterMarker,params,vector,"수정 후");
   }
  }
 }
 public static void main(String[]args){try{if(args[0].equals("seed"))seed();else measure();emit("실행 종료","판정","통과");}catch(Exception e){emit("실행 중단","판정","불가","분류",e.getClass().getSimpleName(),"SQLState",e instanceof SQLException?((SQLException)e).getSQLState():null);System.exit(1);}}
}
