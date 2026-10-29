package com.fastshare.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** A plain list of rows (icon, two lines of text, optional section headers) used by the secondary screens. */
final class RowAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    static final class Row {
        final String id;
        final boolean header;
        String title = "";
        String sub = "";
        int icon = R.drawable.ic_file;
        int color;                // 0 = pick from kind
        FileOps.Kind kind = FileOps.Kind.OTHER;
        File thumb;               // show a photo/video preview when set
        Object tag;

        private Row(String id, boolean header) {
            this.id = id;
            this.header = header;
        }

        static Row header(String title) {
            Row r = new Row("header:" + title, true);
            r.title = title;
            return r;
        }

        static Row item(String id, String title, String sub, FileOps.Kind kind) {
            Row r = new Row(id, false);
            r.title = title;
            r.sub = sub;
            r.kind = kind;
            r.icon = Kinds.icon(kind);
            return r;
        }

        Row tag(Object tag) {
            this.tag = tag;
            return this;
        }

        Row icon(int icon, int color) {
            this.icon = icon;
            this.color = color;
            return this;
        }

        Row thumb(File f) {
            this.thumb = f;
            return this;
        }
    }

    interface Callbacks {
        void onClick(Row row);
        void onLongClick(Row row);
    }

    private static final int TYPE_ROW = 0;
    private static final int TYPE_HEADER = 1;

    private final Context ctx;
    private final Set<String> selected;
    private final Callbacks callbacks;
    private List<Row> rows = new ArrayList<>();
    private final float radius;

    RowAdapter(Context ctx, Set<String> selected, Callbacks callbacks) {
        this.ctx = ctx;
        this.selected = selected;
        this.callbacks = callbacks;
        this.radius = 12 * ctx.getResources().getDisplayMetrics().density;
    }

    void setRows(List<Row> rows) {
        this.rows = rows;
        notifyDataSetChanged();
    }

    List<Row> rows() {
        return rows;
    }

    @Override public int getItemCount() { return rows.size(); }

    @Override public int getItemViewType(int position) { return rows.get(position).header ? TYPE_HEADER : TYPE_ROW; }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new RecyclerView.ViewHolder(inf.inflate(R.layout.item_header, parent, false)) { };
        }
        View v = inf.inflate(R.layout.item_file, parent, false);
        final ImageView icon = v.findViewById(R.id.icon);
        icon.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        icon.setClipToOutline(true);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int position) {
        final Row row = rows.get(position);
        if (row.header) {
            ((TextView) vh.itemView).setText(row.title);
            return;
        }
        final Holder h = (Holder) vh;
        h.name.setText(row.title);
        h.sub.setText(row.sub);
        h.sub.setVisibility(row.sub == null || row.sub.isEmpty() ? View.GONE : View.VISIBLE);
        boolean sel = selected.contains(row.id);
        h.itemView.setActivated(sel);
        h.bound = row.thumb;
        int color = row.color != 0 ? row.color : Kinds.color(ctx, row.kind);
        int pad = (int) (10 * ctx.getResources().getDisplayMetrics().density);
        h.icon.setPadding(pad, pad, pad, pad);
        h.icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        if (sel) {
            h.icon.setBackground(Kinds.solid(ContextCompat.getColor(ctx, R.color.fs_primary), radius));
            h.icon.setImageResource(R.drawable.ic_check);
            h.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.fs_on_primary));
        } else {
            h.icon.setBackground(Kinds.badge(color, radius, false));
            h.icon.setImageResource(row.icon);
            h.icon.setColorFilter(color);
            if (row.thumb != null && (row.kind == FileOps.Kind.IMAGE || row.kind == FileOps.Kind.VIDEO)) {
                Bitmap cached = Thumbs.cached(row.thumb);
                if (cached != null) {
                    showThumb(h.icon, cached);
                } else {
                    final File target = row.thumb;
                    Thumbs.load(target, row.kind == FileOps.Kind.VIDEO, pad * 9, new Thumbs.Done() {
                        @Override public void show(Bitmap b) {
                            if (target.equals(h.bound) && !selected.contains(row.id)) showThumb(h.icon, b);
                        }
                    });
                }
            }
        }
        h.itemView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { callbacks.onClick(row); }
        });
        h.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                callbacks.onLongClick(row);
                return true;
            }
        });
    }

    private void showThumb(ImageView icon, Bitmap b) {
        icon.setColorFilter(null);
        icon.setPadding(0, 0, 0, 0);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setBackgroundColor(Color.TRANSPARENT);
        icon.setImageBitmap(b);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final TextView sub;
        File bound;

        Holder(View v) {
            super(v);
            icon = v.findViewById(R.id.icon);
            name = v.findViewById(R.id.name);
            sub = v.findViewById(R.id.sub);
        }
    }
}
