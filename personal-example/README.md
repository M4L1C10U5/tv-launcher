# Personal overrides (kept out of git)

Copy this folder to `personal/` (which is in `.gitignore`) to swap in your own files at build time
without changing anything in the repository.

Anything in `personal/res/` replaces the file with the same name in `app/src/main/res/`. For example,
to use your own picture as the built-in default wallpaper:

```
personal/res/drawable-nodpi/wallpaper_default.png
```

(Wallpapers can also be picked on the TV at any time: ⚙ → Wallpaper.)
