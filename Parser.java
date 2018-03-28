import java.util.*;

public class Parser {

    private Symtab symtab = new Symtab();
    private boolean stop_called = false; // check if statements following stop statement
    private int max_count = 0; // track max() depths
    private int loop_counter = 0; // track fa and do loops depth

    // check if break found and if error message printed for statements after break
    private boolean break_parsed = false;
    private boolean break_error_printed = false;

    private Stack<Integer> loop_stack = new Stack<Integer>(); // determines label names for goto's
    private int total_loops = 0; // tracks number of loops in program, and helps form labels for goto's

    private int E_excnt_index = 0; // used to count how many EXCNT's have been found
    private int[] E_excnt_index_array = new int[100]; // array of line numbers where EXCNTs are found

    // the first sets.
    // note: we cheat sometimes:
    // when there is only a single token in the set,
    // we generally just compare tkrep with the first token.
    TK f_declarations[] = {TK.VAR, TK.none};
    TK f_statement[] = {TK.ID, TK.PRINT, TK.SKIP, TK.STOP, TK.IF, TK.DO, TK.FA, TK.BREAK, TK.DUMP, TK.EXCNT, TK.none};
    TK f_print[] = {TK.PRINT, TK.none};
    TK f_assignment[] = {TK.ID, TK.none};
    TK f_if[] = {TK.IF, TK.none};
    TK f_do[] = {TK.DO, TK.none};
    TK f_fa[] = {TK.FA, TK.none};
    TK f_expression[] = {TK.ID, TK.NUM, TK.LPAREN, TK.none};
    TK f_skip[] = {TK.SKIP, TK.none};
    TK f_stop[] = {TK.STOP, TK.none};
    TK f_predef[] = {TK.MODULO, TK.MAX, TK.none};
    TK f_break[] = {TK.BREAK, TK.none};
    TK f_dump[] = {TK.DUMP, TK.none};
    TK f_excnt[] = {TK.EXCNT, TK.none};

    // tok is global to all these parsing methods;
    // scan just calls the scanner's scan method and saves the result in tok.
    private Token tok; // the current token
    private void scan() {
        tok = scanner.scan();
    }

    private Scan scanner;
    Parser(Scan scanner) {
        this.scanner = scanner;
        scan();
        program();
        if( tok.kind != TK.EOF )
            parse_error("junk after logical end of program");
        symtab.reportVariables();
    }

    // print something in the generated code
    private void gcprint(String str) {
        System.out.println(str);
    }
    // print identifier in the generated code
    // it prefixes x_ in case id conflicts with C keyword.
    private void gcprintid(String str) {
        System.out.println("x_"+str);
    }

    private void program() {
        // generate the E math functions:
        gcprint("#define MAX(x,y) ((x>y)?x:y)");
        gcprint("int esquare(int x){ return x*x;}");
        gcprint("#include <math.h>");
        gcprint("int esqrt(int x){ double y; if (x < 0) return 0; y = sqrt((double)x); return (int)y;}");

        gcprint("#include <stdio.h>");
        gcprint("#include <stdlib.h>");
        gcprint("int has_excnt = 0;");
        gcprint("struct excnt {int count; int E_line;};");
        gcprint("struct excnt excnts[100];");

        // could be improved but save for later if time
        gcprint("int mod(int a, int b) {" +
                        "if (b == 0) {" +
                            "printf(\"\\nmod(a,b) with b=0\\n\");" +
                            "exit(1);" +
                        "}" +
                        "if (a < 0 && b > 0) {" +
                            "while (a < 0) {a += b;}" +
                            "return(a);" +
                        "}" +
                        "if (a > 0 && b < 0) {" +
                        "while (a > 0) {a += b;}" +
                        "return(a);" +
                        "}" +
                        "int rem = a % b;" +
                        "if (b < 0 && rem > 0) {" +
                            "rem = rem * -1;" +
                        "} else if (b > 0 && rem < 0) {" +
                            "rem = rem * -1;" +
                        "}" +
                        "return(rem);" +
                    "}");

        // C function to show that an EXCNT is in the E program, and to count corresponding EXCNT
        gcprint("void excnt(int index, int line) {" +
                        "has_excnt = 1;" +
                        "excnts[index].count++;" +
                        "excnts[index].E_line = line;" +
                     "}");

        // prototype bc a needed variable won't have the correct value until the end of translation
        gcprint("void print_excnts();");

        gcprint("int main() {");
	block();
	    gcprint("if (has_excnt) {print_excnts();}");
        gcprint("return 0; }");

        // bc I'm a bad programmer and couldn't think of something better
        // this basically records EXCNT E line numbers in an array to be transferred to C array
        gcprint("void set_E_lines() {");
        for (int i = 0; i < E_excnt_index_array.length; i++) {
            gcprint("excnts[" + i + "].E_line = " + E_excnt_index_array[i] + ";");
        }
        gcprint("}");

        // C function to print all the counts of EXCNT's in the program
        gcprint("void print_excnts() {" +
                "set_E_lines();" +
                "printf(\"---- Execution Counts ----\\n\");" +
                "printf(\" num line    count\\n\");" +
                "for (int i = 0; i < " + E_excnt_index + "; i++) {" +
                    "printf(\"%4d%5d%9d\\n\", i+1, excnts[i].E_line, excnts[i].count);" +
                "}" +
                "}");
    }

    private void block() {
        symtab.begin_st_block();
	gcprint("{");
        if( first(f_declarations) ) {
            declarations();
        }
        statement_list();
        symtab.end_st_block();
	gcprint("}");
    }

    private void declarations() {
        mustbe(TK.VAR);
        while( is(TK.ID) ) {
            if( symtab.add_var_entry(tok.string,tok.lineNumber) ) {
                gcprint("int");
                gcprintid(tok.string);
                gcprint("= -12345;");
            }
            scan();
        }
        mustbe(TK.RAV);
    }

    private void statement_list(){
        while( first(f_statement) ) {
            if (stop_called) {
                System.err.println("warning: on line " + tok.lineNumber + " statement(s) follows stop statement");
                stop_called = false;
            }
            if (break_parsed && !break_error_printed) {
                System.err.println("warning: on line " + tok.lineNumber + " statement(s) follows break statement");
                break_error_printed = true;
            }
            statement();
        }
        stop_called = false;
        break_parsed = false;
        break_error_printed = false;
    }

    private void statement(){
        if( first(f_assignment) )
            assignment();
        else if( first(f_print) )
            print();
        else if (first(f_skip))
            skip();
        else if (first(f_stop))
            stop();
        else if( first(f_if) )
            ifproc();
        else if( first(f_do) )
            doproc();
        else if( first(f_fa) )
            fa();
        else if (first(f_break))
            break_statement();
        else if (first(f_dump))
            dump();
        else if (first(f_excnt)) {
            excnt();
        }
        else
            parse_error("statement");
    }

    private void excnt() {
        if (E_excnt_index >= 100) {
            System.err.println("can't parse: line " + tok.lineNumber + " too many EXCNT (more than 100)");
            System.exit(1);
        }
        E_excnt_index_array[E_excnt_index] = tok.lineNumber;
        gcprint("excnt(" + E_excnt_index + "," + tok.lineNumber + ");");
        E_excnt_index++;
        mustbe(TK.EXCNT);
    }

    private void dump() {
        int dump_line = tok.lineNumber;
        mustbe(TK.DUMP);
        if (is(TK.NUM)) {
            int search_level = Integer.parseInt(tok.string);
            if (search_level > symtab.level) {
                System.err.println("warning: on line " + tok.lineNumber + " dump statement level (" +
                        search_level + ") exceeds block nesting level (" + symtab.level + "). using " + symtab.level);
                search_level = symtab.level;
            }
            gcprint("printf(\"+++ dump on line " + tok.lineNumber+ " of level " + search_level+ " begin +++\\n\");");
            for (ArrayList<Entry> list : symtab.st) {
                for (Entry entry : list) {
                    if (entry.level == search_level) {
                        gcprint("printf(\"%12d %3d %3d %s\\n\", x_" + entry.name + "," +
                                entry.linenumber + "," + entry.level + "," + "\""+ entry.name + "\");");
                    }
                }
            }
            gcprint("printf(\"--- dump on line " + tok.lineNumber+ " of level " + search_level+ " end ---\\n\");");
            scan();

        } else {
            gcprint("printf(\"+++ dump on line " + dump_line + " of all levels begin +++\\n\");");
            for (ArrayList<Entry> list : symtab.st) {
                for (Entry entry : list) {
                    gcprint("printf(\"%12d %3d %3d %s\\n\", x_" + entry.name + "," +
                            entry.linenumber + "," + entry.level + "," + "\""+ entry.name + "\");");
                }
            }
            gcprint("printf(\"--- dump on line " + dump_line + " of all levels end ---\\n\");");
        }
    }

    private void break_statement() {
        int break_line = tok.lineNumber;
        mustbe(TK.BREAK);
        if (is(TK.NUM)){
            int break_num = Integer.parseInt(tok.string);
            if ( break_num == 0 ) {
                System.err.println("warning: on line " + break_line + " break 0 statement ignored");
            } else if ( break_num > loop_counter) {
                System.err.println("warning: on line " + break_line + " break statement exceeding loop nesting ignored");
            } else {
                gcprint("goto label_" + loop_stack.elementAt(loop_stack.size() - 1 - break_num + 1) + ";");
                break_parsed = true;
            }
            scan();
            return;
        }
        if (loop_counter > 0) {
            gcprint("break;");
            break_parsed = true;
        } else {
            System.err.println("warning: on line " + break_line + " break statement outside of loop ignored");
        }
        //mustbe(TK.BREAK);
    }

    private void assignment(){
        String id = tok.string;
        int lno = tok.lineNumber; // save it too before mustbe!
        mustbe(TK.ID);
        referenced_id(id, true, lno);
        gcprintid(id);
        mustbe(TK.ASSIGN);
        gcprint("=");
        expression();
        gcprint(";");
    }

    private void print(){
        mustbe(TK.PRINT);
        gcprint("printf(\"%d\\n\", ");
        expression();
        gcprint(");");
        max_count = 0;
    }

    private void skip() {
        mustbe(TK.SKIP);
        gcprint("{}");
    }

    private void stop() {
        stop_called = true;
        mustbe(TK.STOP);
        gcprint("if (has_excnt) {print_excnts();}");
        gcprint("exit(0);");
    }

    private void ifproc(){
        mustbe(TK.IF);
        guarded_commands(TK.IF);
        mustbe(TK.FI);
    }

    private void doproc(){
        total_loops++;
        loop_stack.push(total_loops);
        loop_counter++;
        mustbe(TK.DO);
        gcprint("while(1){");
        guarded_commands(TK.DO);
        gcprint("}");
        mustbe(TK.OD);
        gcprint("label_" + loop_stack.pop()+ ":;");
        loop_counter--;
    }

    private void fa(){
        total_loops++;
        loop_stack.push(total_loops);
        loop_counter++;
        mustbe(TK.FA);
        gcprint("for(");
        String id = tok.string;
        int lno = tok.lineNumber; // save it too before mustbe!
        mustbe(TK.ID);
        referenced_id(id, true, lno);
        gcprintid(id);
        mustbe(TK.ASSIGN);
        gcprint("=");
        expression();
        gcprint(";");
        mustbe(TK.TO);
        gcprintid(id);
        gcprint("<=");
        expression();
        gcprint(";");
        gcprintid(id);
        gcprint("++)");
        if( is(TK.ST) ) {
            gcprint("if( ");
            scan();
            expression();
            gcprint(")");
        }
        commands();
        mustbe(TK.AF);
        gcprint("label_" + loop_stack.pop()+ ":;");
        loop_counter--;
    }

    private void guarded_commands(TK which){
        guarded_command();
        while( is(TK.BOX) ) {
            scan();
            gcprint("else");
            guarded_command();
        }
        if( is(TK.ELSE) ) {
            gcprint("else");
            scan();
            commands();
        }
        else if( which == TK.DO )
            gcprint("else break;");
    }

    private void guarded_command(){
        gcprint("if(");
        expression();
        gcprint(")");
        commands();
    }

    private void commands(){
        mustbe(TK.ARROW);
        gcprint("{");/* note: generate {} to simplify, e.g., st in fa. */
        block();
        gcprint("}");
    }

    private void expression(){
        simple();
        while( is(TK.EQ) || is(TK.LT) || is(TK.GT) ||
               is(TK.NE) || is(TK.LE) || is(TK.GE)) {
            if( is(TK.EQ) ) gcprint("==");
            else if( is(TK.NE) ) gcprint("!=");
            else gcprint(tok.string);
            scan();
            simple();
        }
    }

    private void simple(){
        term();
        while( is(TK.PLUS) || is(TK.MINUS) ) {
            gcprint(tok.string);
            scan();
            term();
        }
    }

    private void term(){
        factor();
        while(  is(TK.TIMES) || is(TK.DIVIDE) || is(TK.REMAINDER) ) {
            gcprint(tok.string);
            scan();
            factor();
        }
    }

    private void factor(){
        if( is(TK.LPAREN) ) {
            gcprint("(");
            scan();
            expression();
            mustbe(TK.RPAREN);
            gcprint(")");
        }
        else if( is(TK.SQUARE) ) {
            gcprint("esquare(");
            scan();
            expression();
            gcprint(")");
        }
        else if( is(TK.SQRT) ) {
            gcprint("esqrt(");
            scan();
            expression();
            gcprint(")");
        }
        else if( is(TK.ID) ) {
            referenced_id(tok.string, false, tok.lineNumber);
            gcprintid(tok.string);
            scan();
        }
        else if( is(TK.NUM) ) {
            gcprint(tok.string);
            scan();
        }
        else if ( first(f_predef)) {
            predef();
        }
        else
            parse_error("factor");
    }

    private void predef() {
        if (is(TK.MODULO)) {
            mustbe(TK.MODULO);
            mustbe(TK.LPAREN);
            gcprint("mod(");
            expression();
            mustbe(TK.COMMA);
            gcprint(",");
            expression();
            mustbe(TK.RPAREN);
            gcprint(")");
        } else if (is(TK.MAX)) {
            max_count++;
            if (max_count > 5)  {
                System.err.println("can't parse: line " + tok.lineNumber + " max expressions nested too deeply (> 5 deep)");
                System.exit(1);
            }
            mustbe(TK.MAX);
            mustbe(TK.LPAREN);
            gcprint("MAX(");
            expression();
            mustbe(TK.COMMA);
            gcprint(",");
            expression();
            if (is(TK.RPAREN)) {
                max_count--;
            }
            mustbe(TK.RPAREN);
            gcprint(")");
        }
    }

    // be careful: use lno here, not tok.lineNumber
    // (which may have been advanced by now)
    private void referenced_id(String id, boolean assigned, int lno) {
        Entry e = symtab.search(id);
        if( e == null) {
            System.err.println("undeclared variable "+ id + " on line "
                               + lno);
            System.exit(1);
        }
        e.ref(assigned, lno);
    }

    // is current token what we want?
    private boolean is(TK tk) {
        return tk == tok.kind;
    }

    // ensure current token is tk and skip over it.
    private void mustbe(TK tk) {
        if( ! is(tk) ) {
            System.err.println( "mustbe: want " + tk + ", got " +
                                    tok);
            parse_error( "missing token (mustbe)" );
        }
        scan();
    }
    boolean first(TK [] set) {
        int k = 0;
        while(set[k] != TK.none && set[k] != tok.kind) {
            k++;
        }
        return set[k] != TK.none;
    }

    private void parse_error(String msg) {
        System.err.println( "can't parse: line "
                            + tok.lineNumber + " " + msg );
        System.exit(1);
    }
}
