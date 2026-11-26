#!/bin/bash
set -e

echo "=== Installing vim-plug ==="
plug_url=https://raw.githubusercontent.com/junegunn/vim-plug/master/plug.vim
if [[ -n "${CI_PACKAGE_ENDPOINT:-}" ]]; then
    broker="http://${CI_PACKAGE_ENDPOINT}"
    plug_url="$broker/console-editor/plug.vim"
fi
curl -fLo ~/.vim/autoload/plug.vim --create-dirs "$plug_url"

echo "=== Configuring ~/.vimrc ==="
cat > ~/.vimrc << 'EOF'
call plug#begin('~/.vim/plugged')
Plug 'neoclide/coc.nvim', {'branch': 'release'}
Plug 'tpope/vim-commentary'
call plug#end()

" Enable sign column (where error indicators appear)
set signcolumn=yes

" Show diagnostic messages faster (this is the key setting for responsiveness)
set updatetime=100

" Highlight errors
highlight CocErrorSign ctermfg=Red guifg=#ff0000
highlight CocWarningSign ctermfg=Yellow guifg=#ffff00

" Press K to show error details
function! ShowDocumentation()
  if CocAction('hasProvider', 'hover')
    call CocActionAsync('doHover')
  endif
endfunction
nnoremap <silent> K :call ShowDocumentation()<CR>

" Navigate between diagnostics with [g and ]g
nmap <silent> [g <Plug>(coc-diagnostic-prev)
nmap <silent> ]g <Plug>(coc-diagnostic-next)

" Show all diagnostics with <space>d
nnoremap <silent> <space>d :<C-u>CocList diagnostics<cr>

" Status line shows error count
set statusline^=%{coc#status()}

" Aggressively refresh diagnostics in insert mode
autocmd TextChangedI * call CocActionAsync('diagnosticRefresh')
autocmd TextChangedP * call CocActionAsync('diagnosticRefresh')

" Also refresh on cursor movement in insert mode
autocmd CursorMovedI * call CocActionAsync('diagnosticRefresh')
EOF

echo "=== Creating coc-settings.json ==="
mkdir -p ~/.vim
cat > ~/.vim/coc-settings.json << 'EOF'
{
  "diagnostic.refreshOnInsertMode": true,
  "diagnostic.checkCurrentLine": true,
  "diagnostic.virtualText": true,
  "diagnostic.messageDelay": 100,
  "diagnostic.refreshAfterSave": false,
  "yaml.validate": true,
  "yaml.hover": true,
  "yaml.completion": true,
  "yaml.format.enable": true,
  "yaml.trace.server": "verbose"
}
EOF

echo "=== Installing vim plugins ==="
if [[ -n "${CI_PACKAGE_ENDPOINT:-}" ]]; then
    set -o pipefail
    for plugin in coc.nvim vim-commentary; do
        mkdir -p "$HOME/.vim/plugged/$plugin"
        curl -fsSL "$broker/console-editor/$plugin.tar.gz" | tar -xz --strip-components=1 -C "$HOME/.vim/plugged/$plugin"
    done
    extensions="$HOME/.config/coc/extensions"
    mkdir -p "$extensions/node_modules"
    for spec in coc-yaml@1.9.3 prettier@2.0.5; do
        name=${spec%@*}
        mkdir -p "$extensions/node_modules/$name"
        curl -fsSL "$broker/npm/$name/-/$name-${spec#*@}.tgz" | tar -xz --strip-components=1 -C "$extensions/node_modules/$name"
    done
    echo '{"dependencies":{"coc-yaml":"1.9.3"}}' > "$extensions/package.json"
else
    vim +PlugInstall +qall
    vim -c 'CocInstall -sync coc-yaml' +qall
fi

echo ""
echo "=== Vim setup complete ==="
echo ""
echo "To use a schema with your YAML files, add this line at the top:"
echo "# yaml-language-server: \$schema=/path/to/your/schema.json"
echo ""
echo "Or configure schemas in ~/.vim/coc-settings.json like:"
echo '  "yaml.schemas": {'
echo '    "file:///path/to/schema.json": "*.config.yaml"'
echo '  }'