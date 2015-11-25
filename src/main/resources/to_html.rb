require 'rubygems'
require 'kramdown'
require 'rouge'

def md_to_html(text)
  syntax_highlighter_opts = Hash.new
  syntax_highlighter_opts[:line_numbers] = false

  return Kramdown::Document.new(text, :input => 'GFM', :line_width => -1, :syntax_highlighter => 'rouge', :syntax_highlighter_opts => syntax_highlighter_opts).to_html
end
