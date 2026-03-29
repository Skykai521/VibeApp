package $packagename;

import com.tencent.shadow.core.runtime.ShadowActivity;
import android.os.Bundle;

public class MainActivity extends ShadowActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }
}
