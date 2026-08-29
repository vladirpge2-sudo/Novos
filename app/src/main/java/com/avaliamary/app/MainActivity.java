package com.avaliamary.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_GALLERY=101, REQ_CAMERA=102, REQ_CAMERA_PERMISSION=103;
    private static final String ML_HOME="https://www.mercadolivre.com.br/";
    private static final String API_BASE="https://avalia-mary-murfa0.v2.appdeploy.ai";
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private LinearLayout formPanel, browserPanel;
    private ScrollView formScroll;
    private ImageView preview;
    private EditText note;
    private TextView status;
    private WebView webView;
    private Uri selectedUri, cameraUri;
    private String pendingReview="", pendingImageBase64="", pendingImageMime="image/png";
    private boolean pendingAutomation=false;

    @Override public void onCreate(Bundle b){super.onCreate(b);prefs=getSharedPreferences("avalia_mary",MODE_PRIVATE);buildUi();setupWebView();showForm();}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}    
    private TextView text(String s,int size,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(0xff2a201d);v.setPadding(0,dp(4),0,dp(4));if(bold)v.setTypeface(null,1);return v;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(16);return b;}

    private void buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xfff7f3ee);
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(dp(12),dp(8),dp(12),dp(8));top.setBackgroundColor(0xff2a201d);
        TextView title=text("Avalia Mary",20,true);title.setTextColor(0xffffffff);top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button formBtn=button("Avaliar");Button mlBtn=button("Mercado Livre");top.addView(formBtn);top.addView(mlBtn);root.addView(top);

        formPanel=new LinearLayout(this);formPanel.setOrientation(LinearLayout.VERTICAL);formPanel.setPadding(dp(16),dp(14),dp(16),dp(24));
        formScroll=new ScrollView(this);formScroll.addView(formPanel);root.addView(formScroll,new LinearLayout.LayoutParams(-1,0,1));
        formPanel.addView(text("Foto → avaliação → Mercado Livre",26,true));
        TextView desc=text("Escolha ou tire a foto, conte só o que quiser e toque no botão. O app cria a avaliação, prepara a foto e abre sua tela salva do Mercado Livre para o agente preencher.",15,false);desc.setTextColor(0xff6b5d56);formPanel.addView(desc);
        preview=new ImageView(this);preview.setAdjustViewBounds(true);preview.setBackgroundColor(0xffeee7e2);formPanel.addView(preview,new LinearLayout.LayoutParams(-1,dp(260)));
        LinearLayout photoRow=new LinearLayout(this);photoRow.setOrientation(LinearLayout.HORIZONTAL);Button cam=button("📷 Tirar foto");Button gal=button("🖼 Galeria");photoRow.addView(cam,new LinearLayout.LayoutParams(0,-2,1));photoRow.addView(gal,new LinearLayout.LayoutParams(0,-2,1));formPanel.addView(photoRow);
        note=new EditText(this);note.setHint("Ex.: custo-benefício muito bom, já estou usando e gostei…");note.setMinLines(3);note.setGravity(Gravity.TOP);formPanel.addView(note,new LinearLayout.LayoutParams(-1,-2));
        Button run=button("✨ FAÇA AS AVALIAÇÕES");run.setTextSize(18);run.setPadding(dp(8),dp(14),dp(8),dp(14));formPanel.addView(run);
        Button openSetup=button("Configurar / abrir minha tela de avaliações");formPanel.addView(openSetup);
        status=text("",14,true);formPanel.addView(status);

        browserPanel=new LinearLayout(this);browserPanel.setOrientation(LinearLayout.VERTICAL);browserPanel.setVisibility(View.GONE);
        LinearLayout bar=new LinearLayout(this);bar.setPadding(dp(8),dp(6),dp(8),dp(6));Button savePage=button("💾 Salvar esta tela");Button fill=button("✨ Preencher agora");bar.addView(savePage,new LinearLayout.LayoutParams(0,-2,1));bar.addView(fill,new LinearLayout.LayoutParams(0,-2,1));browserPanel.addView(bar);
        webView=new WebView(this);browserPanel.addView(webView,new LinearLayout.LayoutParams(-1,0,1));root.addView(browserPanel,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        formBtn.setOnClickListener(v->showForm());mlBtn.setOnClickListener(v->showBrowser(prefs.getString("eval_url",ML_HOME)));
        gal.setOnClickListener(v->pickGallery());cam.setOnClickListener(v->takePhoto());run.setOnClickListener(v->runAll());openSetup.setOnClickListener(v->showBrowser(prefs.getString("eval_url",ML_HOME)));
        savePage.setOnClickListener(v->{String u=webView.getUrl();if(u!=null&&u.contains("mercadolivre.com.br")){prefs.edit().putString("eval_url",u).apply();toast("Esta tela foi salva como sua página de avaliações.");}else toast("Abra primeiro uma página do Mercado Livre.");});
        fill.setOnClickListener(v->{if(pendingReview.isEmpty())toast("Faça uma avaliação primeiro.");else injectAgent();});
    }

    private void setupWebView(){
        WebSettings s=webView.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setDatabaseEnabled(true);s.setSupportMultipleWindows(false);s.setJavaScriptCanOpenWindowsAutomatically(true);s.setLoadWithOverviewMode(true);s.setUseWideViewPort(true);
        CookieManager cm=CookieManager.getInstance();cm.setAcceptCookie(true);cm.setAcceptThirdPartyCookies(webView,true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient(){@Override public void onPageFinished(WebView v,String url){super.onPageFinished(v,url);if(pendingAutomation&&url!=null&&url.contains("mercadolivre.com.br")){v.postDelayed(()->injectAgent(),1800);v.postDelayed(()->injectAgent(),4500);}}});
    }
    private void showForm(){formScroll.setVisibility(View.VISIBLE);browserPanel.setVisibility(View.GONE);}
    private void showBrowser(String url){formScroll.setVisibility(View.GONE);browserPanel.setVisibility(View.VISIBLE);if(url==null||url.isEmpty())url=ML_HOME;if(webView.getUrl()==null||!webView.getUrl().equals(url))webView.loadUrl(url);}
    private void pickGallery(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("image/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,REQ_GALLERY);}
    private void takePhoto(){if(Build.VERSION.SDK_INT>=23&&checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.CAMERA},REQ_CAMERA_PERMISSION);return;}launchCamera();}
    private void launchCamera(){ContentValues cv=new ContentValues();cv.put(MediaStore.Images.Media.DISPLAY_NAME,"avalia_mary_"+System.currentTimeMillis()+".jpg");cv.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");if(Build.VERSION.SDK_INT>=29)cv.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/AvaliaMary");cameraUri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,cv);Intent i=new Intent(MediaStore.ACTION_IMAGE_CAPTURE);i.putExtra(MediaStore.EXTRA_OUTPUT,cameraUri);startActivityForResult(i,REQ_CAMERA);}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==REQ_CAMERA_PERMISSION&&g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED)launchCamera();}
    @Override protected void onActivityResult(int r,int c,Intent d){super.onActivityResult(r,c,d);if(c!=RESULT_OK)return;if(r==REQ_GALLERY&&d!=null){selectedUri=d.getData();try{getContentResolver().takePersistableUriPermission(selectedUri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}preview.setImageURI(selectedUri);}else if(r==REQ_CAMERA){selectedUri=cameraUri;preview.setImageURI(selectedUri);}}

    private String imageBase64(Uri uri)throws Exception{try(InputStream in=getContentResolver().openInputStream(uri)){Bitmap b=BitmapFactory.decodeStream(in);int w=b.getWidth(),h=b.getHeight();float scale=Math.min(1f,1600f/Math.max(w,h));if(w*h*scale*scale>2000000f)scale=(float)Math.sqrt(2000000f/(w*(double)h));if(scale<1f)b=Bitmap.createScaledBitmap(b,Math.round(w*scale),Math.round(h*scale),true);ByteArrayOutputStream out=new ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.JPEG,84,out);return Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);}}
    private JSONObject post(String path,JSONObject body)throws Exception{URL u=new URL(API_BASE+path);HttpURLConnection c=(HttpURLConnection)u.openConnection();c.setRequestMethod("POST");c.setConnectTimeout(30000);c.setReadTimeout(150000);c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setDoOutput(true);byte[] data=body.toString().getBytes(StandardCharsets.UTF_8);try(OutputStream o=c.getOutputStream()){o.write(data);}int code=c.getResponseCode();InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();String txt=new String(in.readAllBytes(),StandardCharsets.UTF_8);if(code<200||code>=300)throw new Exception("HTTP "+code+": "+txt);return new JSONObject(txt);}
    private void runAll(){if(selectedUri==null){toast("Escolha ou tire uma foto primeiro.");return;}status.setText("Preparando sua foto e sua avaliação…");pendingAutomation=false;executor.execute(()->{try{String img=imageBase64(selectedUri);JSONObject media=new JSONObject().put("data",img).put("mimeType","image/jpeg");JSONObject reviewReq=new JSONObject().put("image",media).put("note",note.getText().toString());JSONObject photoReq=new JSONObject().put("image",media);JSONObject review=post("/api/review",reviewReq);JSONObject photo=post("/api/photo",photoReq);pendingReview=review.optString("review","");pendingImageBase64=photo.optString("imageData",img);pendingImageMime=photo.optString("mimeType","image/jpeg");pendingAutomation=true;runOnUiThread(()->{status.setText("Pronto. Abrindo sua tela do Mercado Livre para o agente preencher…");String target=prefs.getString("eval_url",ML_HOME);showBrowser(target);if(target.equals(ML_HOME))toast("Na primeira vez, entre no Mercado Livre, abra sua tela de avaliações e toque em ‘Salvar esta tela’. Depois o botão fará o caminho sozinho.");});}catch(Exception e){runOnUiThread(()->status.setText("Não consegui concluir: "+e.getMessage()));}});}
    private String jsQuote(String s){return JSONObject.quote(s);}
    private void injectAgent(){if(pendingReview.isEmpty())return;String js="(function(){try{if(window.__avaliaMarySent)return 'already';const review="+jsQuote(pendingReview)+";const b64="+jsQuote(pendingImageBase64)+";const mime="+jsQuote(pendingImageMime)+";const visible=e=>!!(e&&e.offsetParent!==null);let field=[...document.querySelectorAll('textarea')].find(visible)||[...document.querySelectorAll('[contenteditable=\\\"true\\\"]')].find(visible)||[...document.querySelectorAll('input[type=\\\"text\\\"]')].find(visible);if(field){field.focus();if('value' in field){const d=Object.getOwnPropertyDescriptor(field.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype,'value');if(d&&d.set)d.set.call(field,review);else field.value=review;}else field.textContent=review;field.dispatchEvent(new Event('input',{bubbles:true}));field.dispatchEvent(new Event('change',{bubbles:true}));}let star=[...document.querySelectorAll('[aria-label],[title]')].find(e=>visible(e)&&/5\\s*(estrela|star)/i.test((e.getAttribute('aria-label')||'')+' '+(e.getAttribute('title')||'')));if(star)star.click();let file=document.querySelector('input[type=\\\"file\\\"]');if(file&&b64){const bin=atob(b64),arr=new Uint8Array(bin.length);for(let i=0;i<bin.length;i++)arr[i]=bin.charCodeAt(i);const f=new File([arr],'avaliacao-mary.'+(mime.includes('png')?'png':'jpg'),{type:mime});const dt=new DataTransfer();dt.items.add(f);file.files=dt.files;file.dispatchEvent(new Event('change',{bubbles:true}));}if(!field)return 'campo-nao-encontrado';setTimeout(()=>{let btn=[...document.querySelectorAll('button,[role=\\\"button\\\"],input[type=\\\"submit\\\"]')].find(e=>visible(e)&&/^(enviar|publicar|avaliar|confirmar|continuar)$/i.test(((e.innerText||e.value||e.getAttribute('aria-label')||'').trim())));if(btn){window.__avaliaMarySent=true;btn.click();}},1800);return 'preenchido';}catch(e){return 'erro:'+e.message;}})();";webView.evaluateJavascript(js,val->{if(val!=null){String low=val.toLowerCase(Locale.ROOT);if(low.contains("campo-nao-encontrado"))toast("Ainda não encontrei o campo de avaliação nesta tela. Abra a avaliação do produto e toque em ‘Preencher agora’.");else if(low.contains("preenchido"))toast("Avaliação preenchida. O agente vai enviar quando reconhecer o botão correto.");}});}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override public void onBackPressed(){if(browserPanel.getVisibility()==View.VISIBLE&&webView.canGoBack())webView.goBack();else if(browserPanel.getVisibility()==View.VISIBLE)showForm();else super.onBackPressed();}
    @Override protected void onDestroy(){executor.shutdownNow();super.onDestroy();}
}
