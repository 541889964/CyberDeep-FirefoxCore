package com.cyber.deep;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONObject;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private RecyclerView recyclerView;
    private EditText etInput;
    private TextView tvStatus;
    private GeckoView geckoView;
    private GeckoSession session;
    private static GeckoRuntime runtime;

    private boolean isPageReady = false;
    private boolean isRunning = false;

    private MsgAdapter adapter;
    private final List<Msg> msgList = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final String MY_TOKEN = "WlS6knRr+HvB+h7ioKh4LgxmyfJXJfQ3jJaYfuYzQQ9oOthcRovka6o83lKFspw5";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        recyclerView = findViewById(R.id.recyclerView);
        etInput = findViewById(R.id.etInput);
        ImageButton btnSend = findViewById(R.id.btnSend);
        geckoView = findViewById(R.id.geckoView);
        tvStatus = findViewById(R.id.tvStatus);

        adapter = new MsgAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        initGeckoView();

        btnSend.setOnClickListener(v -> {
            String prompt = etInput.getText().toString().trim();
            if (prompt.isEmpty() || isRunning || !isPageReady) return;
            etInput.setText("");
            addMsg("User", prompt);
            isRunning = true;
            tvStatus.setText("内核生成中...");
            
            // 修复：使用 loadUri 注入 javascript 代替废弃的 evaluateJS
            String js = "javascript:window.cyberSend(" + JSONObject.quote(prompt) + ");";
            session.loadUri(js);
        });
    }

    private void initGeckoView() {
        if (runtime == null) runtime = GeckoRuntime.create(this);
        session = new GeckoSession();

        session.open(runtime);
        geckoView.setSession(session);
        session.loadUri("https://chat.deepseek.com");
        
        mainHandler.postDelayed(this::injectScript, 3000);
    }

    private void injectScript() {
        String js =
            "(function() {" +
            "  localStorage.setItem('userToken', JSON.stringify({value:'" + MY_TOKEN + "', __version:'0'}));" +
            "  if (window.cyberHooked) return;" +
            "  window.cyberHooked = true;" +
            "  window.cyberSend = function(text) {" +
            "    let ta = document.querySelector('textarea');" +
            "    if (!ta) return;" +
            "    let setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;" +
            "    setter.call(ta, text);" +
            "    ta.dispatchEvent(new Event('input', { bubbles: true }));" +
            "    setTimeout(function() {" +
            "      ta.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true}));" +
            "    }, 300);" +
            "  };" +
            "  let lastText = '';" +
            "  setInterval(function() {" +
            "    let blocks = document.querySelectorAll('.ds-markdown');" +
            "    if (blocks.length > 0) {" +
            "      let latest = blocks[blocks.length - 1].innerText;" +
            "      if (latest !== lastText) { " +
            "        lastText = latest; " +
            // 修复：移除废弃的 window.postMessage，改用 DOM 标题变化作为信号
            "        document.title = 'REPLY:' + latest;" +
            "      }" +
            "    }" +
            "  }, 500);" +
            "  document.title = 'READY';" +
            "})();";
        session.loadUri("javascript:" + js);
        
        // 修复：通过 ContentDelegate 监听标题变化，实现原生与网页通信
        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onTitleChange(@NonNull GeckoSession session, String title) {
                if (title == null) return;
                if ("READY".equals(title)) {
                    isPageReady = true;
                    tvStatus.setText("就绪");
                } else if (title.startsWith("REPLY:")) {
                    isRunning = false;
                    tvStatus.setText("就绪");
                    updateLastAiMsg(title.substring(6));
                }
            }
        });
    }

    private void updateLastAiMsg(String fullText) {
        if (msgList.isEmpty() || !"AI".equals(msgList.get(msgList.size() - 1).role)) {
            addMsg("AI", fullText);
        } else {
            Msg last = msgList.get(msgList.size() - 1);
            last.content = fullText;
            adapter.notifyItemChanged(msgList.size() - 1);
            recyclerView.smoothScrollToPosition(msgList.size() - 1);
        }
    }

    private void addMsg(String role, String content) {
        msgList.add(new Msg(role, content));
        adapter.notifyItemInserted(msgList.size() - 1);
        recyclerView.smoothScrollToPosition(msgList.size() - 1);
    }

    class MsgAdapter extends RecyclerView.Adapter<MsgAdapter.VH> {
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull VH holder, int position) {
            Msg msg = msgList.get(position);
            holder.tvContent.setText(msg.content);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if ("User".equals(msg.role)) {
                params.gravity = android.view.Gravity.END;
                holder.tvContent.setBackgroundResource(R.drawable.bg_user_bubble);
                holder.tvContent.setTextColor(0xFFFFFFFF);
            } else {
                params.gravity = android.view.Gravity.START;
                holder.tvContent.setBackgroundResource(R.drawable.bg_ai_bubble);
                holder.tvContent.setTextColor(0xFF00E5FF);
            }
            holder.tvContent.setLayoutParams(params);
        }
        @Override public int getItemCount() { return msgList.size(); }
        class VH extends RecyclerView.ViewHolder { TextView tvContent; VH(View v) { super(v); tvContent = v.findViewById(R.id.tvContent); } }
    }
    static class Msg { String role, content; Msg(String role, String content) { this.role = role; this.content = content; } }
}
