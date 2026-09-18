package ai.cochl.tutorials;

import android.view.View;

public interface OnItemClickListener {
    void OnItemClick(Adapter.ViewHolder viewHolder, View view, int position);
}