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

/** Rows of files and folders. */
final class FileAdapter extends RecyclerView.Adapter<FileAdapter.Holder> {

    interface Callbacks {
        void onClick(FileOps.Item item);
        void onLongClick(FileOps.Item item);
    }

    private final Context ctx;
    private final Set<String> selected;
    private final Callbacks callbacks;
    private List<FileOps.Item> items = new ArrayList<>();
    private boolean showPath;        // search results show where each file lives
    private final float radius;

    FileAdapter(Context ctx, Set<String> selected, Callbacks callbacks) {
        this.ctx = ctx;
        this.selected = selected;
        this.callbacks = callbacks;
        this.radius = 12 * ctx.getResources().getDisplayMetrics().density;
    }

    void setItems(List<FileOps.Item> items, boolean showPath) {
        this.items = items;
        this.showPath = showPath;
        notifyDataSetChanged();
    }

    List<FileOps.Item> items() {
        return items;
    }

    @Override public int getItemCount() { return items.size(); }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_file, parent, false);
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
    public void onBindViewHolder(@NonNull final Holder h, int position) {
        final FileOps.Item item = items.get(position);
        h.name.setText(item.name);

        String date = Fmt.date(item.modified);
        String sub;
        if (item.dir) {
            sub = item.children < 0 ? "Folder" : Fmt.plural(item.children, "item", "items");
        } else {
            sub = Fmt.bytes(item.size);
        }
        if (!date.isEmpty()) sub += "  ·  " + date;
        if (showPath && item.file.getParentFile() != null) {
            String parent = item.file.getParentFile().getName();
            sub = parent + "  ·  " + sub;
        }
        h.sub.setText(sub);

        boolean sel = selected.contains(item.file.getPath());
        h.itemView.setActivated(sel);
        h.bound = item.file;

        int color = Kinds.color(ctx, item.kind);
        if (sel) {
            h.icon.setBackground(Kinds.solid(ContextCompat.getColor(ctx, R.color.fs_primary), radius));
            h.icon.setPadding(pad(10), pad(10), pad(10), pad(10));
            h.icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            h.icon.setImageResource(R.drawable.ic_check);
            h.icon.setColorFilter(ContextCompat.getColor(ctx, R.color.fs_on_primary));
        } else {
            h.icon.setBackground(Kinds.badge(color, radius, false));
            h.icon.setPadding(pad(10), pad(10), pad(10), pad(10));
            h.icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            h.icon.setImageResource(Kinds.icon(item.kind));
            h.icon.setColorFilter(color);
            if (item.kind == FileOps.Kind.IMAGE || item.kind == FileOps.Kind.VIDEO) {
                Bitmap cached = Thumbs.cached(item.file);
                if (cached != null) {
                    showThumb(h.icon, cached);
                } else {
                    final File target = item.file;
                    Thumbs.load(target, item.kind == FileOps.Kind.VIDEO, pad(88), new Thumbs.Done() {
                        @Override public void show(Bitmap b) {
                            if (target.equals(h.bound) && !selected.contains(target.getPath())) showThumb(h.icon, b);
                        }
                    });
                }
            }
        }

        h.itemView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { callbacks.onClick(item); }
        });
        h.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                callbacks.onLongClick(item);
                return true;
            }
        });
    }

    /** Icon (or photo preview) for a file in a list that isn't recycled, such as the home screen's recent files. */
    static void styleIcon(Context ctx, final ImageView icon, FileOps.Item item) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int color = Kinds.color(ctx, item.kind);
        int pad = (int) (10 * density);
        icon.setBackground(Kinds.badge(color, 12 * density, false));
        icon.setPadding(pad, pad, pad, pad);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setImageResource(Kinds.icon(item.kind));
        icon.setColorFilter(color);
        icon.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 12 * view.getResources().getDisplayMetrics().density);
            }
        });
        icon.setClipToOutline(true);
        if (item.kind == FileOps.Kind.IMAGE || item.kind == FileOps.Kind.VIDEO) {
            Bitmap cached = Thumbs.cached(item.file);
            Thumbs.Done done = new Thumbs.Done() {
                @Override public void show(Bitmap b) {
                    icon.setColorFilter(null);
                    icon.setPadding(0, 0, 0, 0);
                    icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    icon.setBackgroundColor(Color.TRANSPARENT);
                    icon.setImageBitmap(b);
                }
            };
            if (cached != null) done.show(cached);
            else Thumbs.load(item.file, item.kind == FileOps.Kind.VIDEO, pad * 9, done);
        }
    }

    private void showThumb(ImageView icon, Bitmap b) {
        icon.setColorFilter(null);
        icon.setPadding(0, 0, 0, 0);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setBackgroundColor(Color.TRANSPARENT);
        icon.setImageBitmap(b);
    }

    private int pad(int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density);
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
