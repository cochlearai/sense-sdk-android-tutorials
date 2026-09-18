package ai.cochl.tutorials;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;

public class Adapter extends RecyclerView.Adapter<Adapter.ViewHolder> implements OnItemClickListener {
    // Parse once, not on every bind.
    private static final int COLOR_SELECTED = Color.parseColor("#40000000");
    private static final int COLOR_UNSELECTED = Color.TRANSPARENT;

    private final ArrayList<Item> items = new ArrayList<>();
    private OnItemClickListener listener;
    private int selectedPosition = -1;

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        View itemView = inflater.inflate(R.layout.item, parent, false);

        return new ViewHolder(itemView, this.listener);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.tv.setBackgroundColor(selectedPosition == position ? COLOR_SELECTED : COLOR_UNSELECTED);
        holder.SetItem(items.get(position));
    }

    @Override
    public void OnItemClick(Adapter.ViewHolder viewHolder, View view, int position) {
        if (listener == null) {
            return;
        }
        listener.OnItemClick(viewHolder, view, position);
    }

    void AddItem(Item item) {
        items.add(item);
    }

    Item GetItem(int position) {
        return items.get(position);
    }

    void SetOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        TextView tv;

        ViewHolder(View itemView, final OnItemClickListener listener) {
            super(itemView);

            tv = itemView.findViewById(R.id.textview);
            itemView.setOnClickListener(view -> {
                if (listener == null) return;
                notifyItemChanged(selectedPosition);
                selectedPosition = getLayoutPosition();
                notifyItemChanged(selectedPosition);
                listener.OnItemClick(ViewHolder.this, view, getAdapterPosition());
            });
        }

        void SetItem(Item item) {
            tv.setText(item.GetFilename());
        }
    }
}