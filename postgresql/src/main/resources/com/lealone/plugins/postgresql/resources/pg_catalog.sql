/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh, H2 Group
 */
 
drop schema if exists pg_catalog;
create schema pg_catalog;

drop alias if exists pg_convertType;
create alias pg_convertType deterministic for "com.lealone.plugins.postgresql.sql.PgAlias.convertType";

drop alias if exists pg_get_oid;
create alias pg_get_oid deterministic for "com.lealone.plugins.postgresql.sql.PgAlias.getOid";

create table pg_catalog.pg_version as select 2 as version, 2 as version_read;
--create table pg_catalog.pg_version(version int, version_read int);
--insert into pg_catalog.pg_version(version,version_read) values(2,2);
grant select on pg_catalog.pg_version to public;

create view pg_catalog.pg_roles
as
select
    id oid,
    cast(lower(name) as varchar_ignorecase) rolname,
    admin rolsuper,
    true rolinherit,
    admin rolcreaterole,
    admin rolcreatedb,
    true rolcanlogin,
    -1 rolconnlimit,
    cast(null as timestamp) rolvaliduntil,
    false rolreplication,
    false rolbypassrls,
    cast(null as array) rolconfig
from information_schema.users;
grant select on pg_catalog.pg_roles to public;

create view pg_catalog.pg_namespace
as
select
    id oid,
    cast(lower(schema_name) as varchar_ignorecase) nspname,
    (select min(id) from information_schema.users where admin=true) nspowner,
    cast(null as array) nspacl
from information_schema.schemas;
grant select on pg_catalog.pg_namespace to public;

create table pg_catalog.pg_type(
    oid int primary key,
    typname varchar_ignorecase,
    typnamespace int,
    typowner int,
    typlen int,
    typbyval boolean,
    typtype varchar,
    typcategory varchar,
    typispreferred boolean,
    typisdefined boolean,
    typdelim varchar,
    typrelid int,
    typelem int,
    typarray int,
    typbasetype int,
    typtypmod int,
    typndims int,
    typnotnull boolean,
    typinput varchar_ignorecase,
    typoutput varchar_ignorecase,
    typreceive varchar_ignorecase,
    typsend varchar_ignorecase,
    typdefault varchar_ignorecase,
    typcollation int,
    typacl array);
grant select on pg_catalog.pg_type to public;

insert into pg_catalog.pg_type
(oid, typname, typnamespace, typowner, typlen, typbyval, typtype, typcategory, typispreferred, typisdefined, typdelim, typrelid, typelem, typarray, typbasetype, typtypmod, typndims, typnotnull, typinput, typoutput, typreceive, typsend, typdefault, typcollation, typacl)
select
    pg_convertType(data_type) oid,
    cast(lower(type_name) as varchar_ignorecase) typname,
    (select oid from pg_catalog.pg_namespace where nspname = 'pg_catalog') typnamespace,
    0 typowner,
    -1 typlen,
    false typbyval,
    'b' typtype,
    'N' typcategory,
    false typispreferred,
    true typisdefined,
    ',' typdelim,
    0 typrelid,
    0 typelem,
    0 typarray,
    0 typbasetype,
    -1 typtypmod,
    0 typndims,
    false typnotnull,
    '' typinput,
    '' typoutput,
    '' typreceive,
    '' typsend,
    cast(null as varchar) typdefault,
    0 typcollation,
    cast(null as array) typacl
from information_schema.type_info
where pos = 0
    and pg_convertType(data_type) <> 705; -- not unknown

merge into pg_catalog.pg_type(oid, typname, typnamespace, typlen, typrelid, typtype, typbasetype, typtypmod) values(
    19,
    'name',
    (select oid from pg_catalog.pg_namespace where nspname = 'pg_catalog'),
    -1,
    0,
    'c',
    0,
    -1
);
merge into pg_catalog.pg_type(oid, typname, typnamespace, typlen, typrelid, typtype, typbasetype, typtypmod) values(
    0,
    'null',
    (select oid from pg_catalog.pg_namespace where nspname = 'pg_catalog'),
    -1,
    0,
    'c',
    0,
    -1
);
merge into pg_catalog.pg_type(oid, typname, typnamespace, typlen, typrelid, typtype, typbasetype, typtypmod) values(
    22,
    'int2vector',
    (select oid from pg_catalog.pg_namespace where nspname = 'pg_catalog'),
    -1,
    0,
    'c',
    0,
    -1
);

-- (oid, relname, relnamespace, relkind, relam, reltuples, relpages, relhasrules, relhasoids)
create view pg_catalog.pg_class
as
select
    t.id oid,
    cast(lower(t.table_name) as varchar_ignorecase) relname,
    (select id from information_schema.schemas where schema_name = t.table_schema) relnamespace,
    case when t.table_type in ('TABLE', 'STANDARD_TABLE', 'BASE TABLE') then 'r' else 'v' end relkind,
    (select min(id) from information_schema.users where admin=true) relowner,
    0 relam,
    cast(0 as float) reltuples,
    0 relpages,
    false relhasrules,
    false relhastriggers,
    false relhasoids,
    case when i.cnt > 0 then true else false end relhasindex,
    false relhassubclass,
    0 relchecks,
    cast(null as array) relacl,
    false relrowsecurity,
    false relforcerowsecurity,
    'p' relpersistence,
    'd' relreplident,
    false relispartition,
    0 relfilenode,
    0 reltablespace,
    0 reloftype,
    0 reltoastrelid,
    0 relnatts,
    cast(null as array) reloptions
from information_schema.tables t
left join (select table_schema, table_name, count(*) cnt from information_schema.indexes
           group by table_schema, table_name) i
       on i.table_schema = t.table_schema and i.table_name = t.table_name
union all
select
    x.id oid,
    cast(lower(x.index_name) as varchar_ignorecase) relname,
    (select id from information_schema.schemas where schema_name = x.table_schema) relnamespace,
    'i' relkind,
    (select min(id) from information_schema.users where admin=true) relowner,
    0 relam,
    cast(0 as float) reltuples,
    0 relpages,
    false relhasrules,
    false relhastriggers,
    false relhasoids,
    false relhasindex,
    false relhassubclass,
    0 relchecks,
    cast(null as array) relacl,
    false relrowsecurity,
    false relforcerowsecurity,
    'p' relpersistence,
    'd' relreplident,
    false relispartition,
    0 relfilenode,
    0 reltablespace,
    0 reloftype,
    0 reltoastrelid,
    0 relnatts,
    cast(null as array) reloptions
from (select distinct id, index_name, table_schema, table_name from information_schema.indexes) x;
grant select on pg_catalog.pg_class to public;

create table pg_catalog.pg_proc(
    oid int,
    proname varchar_ignorecase,
    pronamespace int,
    prorettype int,
    proisagg boolean,
    proiswindow boolean,
    prokind varchar,
    proargtypes varchar_ignorecase,
    pronargs int,
    proowner int,
    prolang int,
    procost float,
    prorows float,
    provariadic int,
    prosrc varchar_ignorecase
);
grant select on pg_catalog.pg_proc to public;

create table pg_catalog.pg_trigger(
    oid int,
    tgconstrrelid int,
    tgfoid int,
    tgargs int,
    tgnargs int,
    tgdeferrable boolean,
    tginitdeferred boolean,
    tgconstrname varchar_ignorecase,
    tgrelid int
);
grant select on pg_catalog.pg_trigger to public;

create view pg_catalog.pg_attrdef -- (oid, adrelid, adnum, adbin, adsrc)
as
select
    cast(t.id as bigint) * 10000 + c.ordinal_position oid,
    t.id adrelid,
    c.ordinal_position adnum,
    cast(c.column_default as varchar_ignorecase) adbin,
    cast(c.column_default as varchar_ignorecase) adsrc
from information_schema.tables t, information_schema.columns c
where t.table_name = c.table_name
and t.table_schema = c.table_schema
and c.column_default is not null;
grant select on pg_catalog.pg_attrdef to public;

-- (oid, attrelid, attname, atttypid, attlen, attnum, atttypmod, attnotnull, attisdropped, atthasdef)
create view pg_catalog.pg_attribute
as
select
    cast(t.id as bigint) * 10000 + c.ordinal_position oid,
    t.id attrelid,
    cast(lower(c.column_name) as varchar_ignorecase) attname,
    pg_convertType(data_type) atttypid,
    case when numeric_precision > 255 then -1 else numeric_precision end attlen,
    c.ordinal_position attnum,
    case when c.character_maximum_length is not null and c.character_maximum_length < 1000000 then c.character_maximum_length + 4 else -1 end atttypmod,
    case c.is_nullable when 'YES' then false else true end attnotnull,
    false attisdropped,
    false atthasdef,
    -1 attstattarget,
    0 attndims,
    -1 attcacheoff,
    false attbyval,
    'p' attstorage,
    'i' attalign,
    true attislocal,
    0 attinhcount,
    0 attcollation,
    false atthasmissing,
    cast(null as array) attacl,
    cast(null as array) attoptions,
    cast(null as array) attfdwoptions
from information_schema.tables t, information_schema.columns c
where t.table_name = c.table_name
and t.table_schema = c.table_schema
union all
select
    1000000 + cast(t.id as bigint) * 10000 + c.ordinal_position oid,
    i.id attrelid,
    cast(lower(c.column_name) as varchar_ignorecase) attname,
    pg_convertType(data_type) atttypid,
    case when numeric_precision > 255 then -1 else numeric_precision end attlen,
    c.ordinal_position attnum,
    case when c.character_maximum_length is not null and c.character_maximum_length < 1000000 then c.character_maximum_length + 4 else -1 end atttypmod,
    case c.is_nullable when 'YES' then false else true end attnotnull,
    false attisdropped,
    false atthasdef,
    -1 attstattarget,
    0 attndims,
    -1 attcacheoff,
    false attbyval,
    'p' attstorage,
    'i' attalign,
    true attislocal,
    0 attinhcount,
    0 attcollation,
    false atthasmissing,
    cast(null as array) attacl,
    cast(null as array) attoptions,
    cast(null as array) attfdwoptions
from information_schema.tables t, information_schema.indexes i, information_schema.columns c
where t.table_name = i.table_name
and t.table_schema = i.table_schema
and t.table_name = c.table_name
and t.table_schema = c.table_schema;
grant select on pg_catalog.pg_attribute to public;

 -- pg_index: psql \d 用它列出索引
create view pg_catalog.pg_index
as
select
    i.id oid,
    i.id indexrelid,
    t.id indrelid,
    false indisclustered,
    not i.non_unique indisunique,
    i.primary_key indisprimary,
    false indisexclusion,
    true indisvalid,
    true indisready,
    true indimmediate,
    false indisreplident,
    cast(1 as int) indnatts,
    cast(1 as int) indnkeyatts,
    cast('1' as varchar_ignorecase) indkey,
    cast(null as varchar) indpred,
    cast(null as varchar) indexprs,
    cast(null as array) indcollation,
    cast(null as array) indclass,
    cast(null as array) indoption
from (select distinct id, index_name, table_schema, table_name, non_unique, primary_key
      from information_schema.indexes) i,
     information_schema.tables t
where i.table_schema = t.table_schema
and i.table_name = t.table_name;
grant select on pg_catalog.pg_index to public;

drop alias if exists pg_get_indexdef;
create alias pg_get_indexdef for "com.lealone.plugins.postgresql.sql.PgAlias.getIndexColumn";

drop alias if exists current_schema;
create alias current_schema for "com.lealone.plugins.postgresql.sql.PgAlias.getCurrentSchema";

drop alias if exists current_schemas;
create alias current_schemas deterministic for "com.lealone.plugins.postgresql.sql.PgAlias.getCurrentSchemas";

drop alias if exists pg_encoding_to_char;
create alias pg_encoding_to_char for "com.lealone.plugins.postgresql.sql.PgAlias.getEncodingName";

drop alias if exists pg_char_to_encoding;
create alias pg_char_to_encoding for "com.lealone.plugins.postgresql.sql.PgAlias.getEncodingCode";

drop alias if exists format_type;
create alias format_type for "com.lealone.plugins.postgresql.sql.PgAlias.formatType";

drop alias if exists pg_postmaster_start_time;
create alias pg_postmaster_start_time for "com.lealone.plugins.postgresql.sql.PgAlias.getStartTime";

drop alias if exists pg_get_userbyid;
create alias pg_get_userbyid for "com.lealone.plugins.postgresql.sql.PgAlias.getUserById";

drop alias if exists has_database_privilege;
create alias has_database_privilege for "com.lealone.plugins.postgresql.sql.PgAlias.hasDatabasePrivilege";

drop alias if exists has_table_privilege;
create alias has_table_privilege for "com.lealone.plugins.postgresql.sql.PgAlias.hasTablePrivilege";

drop alias if exists currtid2;
create alias currtid2 for "com.lealone.plugins.postgresql.sql.PgAlias.getCurrentTid";

create view pg_catalog.pg_database
as
select
    1 oid,
    cast(lower(catalog_name) as varchar_ignorecase) datname,
    (select min(id) from information_schema.users where admin=true) datdba,
    6 encoding,
    cast('en_US.utf8' as varchar_ignorecase) datcollate,
    cast('en_US.utf8' as varchar_ignorecase) datctype,
    false datistemplate,
    true datallowconn,
    -1 datconnlimit,
    100000 datlastsysoid,
    0 datfrozenxid,
    0 datminmxid,
    0 dattablespace,
    cast(null as array) datacl
from information_schema.catalogs;
grant select on pg_catalog.pg_database to public;

create table pg_catalog.pg_tablespace(
    oid int,
    spcname varchar_ignorecase,
    spclocation varchar_ignorecase,
    spcowner int,
    spcacl array -- aclitem[]
);
grant select on pg_catalog.pg_tablespace to public;

insert into pg_catalog.pg_tablespace values(
    0,
    'main', -- spcname
    '?', -- spclocation
    0, -- spcowner,
    null -- spcacl
);

create table pg_catalog.pg_settings(
    oid int,
    name varchar_ignorecase,
    setting varchar_ignorecase
);
grant select on pg_catalog.pg_settings to public;

insert into pg_catalog.pg_settings values
(0, 'autovacuum', 'on'),
(1, 'stats_start_collector', 'on'),
(2, 'stats_row_level', 'on');

create view pg_catalog.pg_user -- oid, usename, usecreatedb, usesuper
as
select
    id oid,
    cast(name as varchar_ignorecase) usename,
    true usecreatedb,
    true usesuper
from information_schema.users;
grant select on pg_catalog.pg_user to public;

create table pg_catalog.pg_authid(
    oid int,
    rolname varchar_ignorecase,
    rolsuper boolean,
    rolinherit boolean,
    rolcreaterole boolean,
    rolcreatedb boolean,
    rolcatupdate boolean,
    rolcanlogin boolean,
    rolconnlimit boolean,
    rolpassword boolean,
    rolvaliduntil timestamp, -- timestamptz
    rolconfig array -- text[]
);
grant select on pg_catalog.pg_authid to public;

create table pg_catalog.pg_am(oid int, amname varchar_ignorecase);
grant select on pg_catalog.pg_am to public;
insert into  pg_catalog.pg_am values(0, 'btree');
insert into  pg_catalog.pg_am values(1, 'hash');

create table pg_catalog.pg_description -- (objoid, objsubid, classoid, description)
as
select
    oid objoid,
    0 objsubid,
    -1 classoid,
    cast(datname as varchar_ignorecase) description
from pg_catalog.pg_database;
grant select on pg_catalog.pg_description to public;

create table pg_catalog.pg_group -- oid, groname
as
select
    0 oid,
    cast('' as varchar_ignorecase) groname
from pg_catalog.pg_database where 1=0;
grant select on pg_catalog.pg_group to public;

create table pg_catalog.pg_conversion(
    oid oid,
    conname varchar_ignorecase,
    connamespace oid,
    conowner oid,
    conforencoding int,
    contoencoding  int,
    conproc oid,
    condefault boolean
);
grant select on pg_catalog.pg_conversion to public;

-- ---------------------------------------------------------------- psql / pgjdbc 需要的辅助函数
drop alias if exists pg_table_is_visible;
create alias pg_table_is_visible deterministic for "com.lealone.plugins.postgresql.sql.PgAlias.pgTableIsVisible";

drop alias if exists pg_function_is_visible;
create alias pg_function_is_visible deterministic for "com.lealone.plugins.postgresql.sql.PgAlias.pgFunctionIsVisible";

drop alias if exists pg_type_is_visible;
create alias pg_type_is_visible deterministic for "com.lealone.plugins.postgresql.sql.PgAlias.pgTypeIsVisible";

drop alias if exists pg_collation_is_visible;
create alias pg_collation_is_visible deterministic for "com.lealone.plugins.postgresql.sql.PgAlias.pgCollationIsVisible";

drop alias if exists has_schema_privilege;
create alias has_schema_privilege deterministic for "com.lealone.plugins.postgresql.sql.PgAlias.hasSchemaPrivilege";

drop alias if exists has_column_privilege;
create alias has_column_privilege deterministic for "com.lealone.plugins.postgresql.sql.PgAlias.hasColumnPrivilege";

drop alias if exists has_function_privilege;
create alias has_function_privilege deterministic for "com.lealone.plugins.postgresql.sql.PgAlias.hasFunctionPrivilege";

drop alias if exists array_to_string;
create alias array_to_string deterministic for "com.lealone.plugins.postgresql.sql.PgAlias.arrayToString";

drop alias if exists pg_get_function_result;
create alias pg_get_function_result deterministic for "com.lealone.plugins.postgresql.sql.PgAlias.getFunctionResult";

drop alias if exists pg_get_function_arguments;
create alias pg_get_function_arguments deterministic for "com.lealone.plugins.postgresql.sql.PgAlias.getFunctionArguments";

drop alias if exists pg_get_function_identity_arguments;
create alias pg_get_function_identity_arguments deterministic for "com.lealone.plugins.postgresql.sql.PgAlias.getFunctionIdentityArguments";

drop alias if exists pg_get_functiondef;
create alias pg_get_functiondef deterministic for "com.lealone.plugins.postgresql.sql.PgAlias.getFunctionDef";

drop alias if exists pg_get_expr;
create alias pg_get_expr deterministic for "com.lealone.plugins.postgresql.sql.PgAlias.getExpr";

drop alias if exists pg_get_constraintdef;
create alias pg_get_constraintdef deterministic for "com.lealone.plugins.postgresql.sql.PgAlias.getConstraintDef";

drop alias if exists pg_get_viewdef;
create alias pg_get_viewdef deterministic for "com.lealone.plugins.postgresql.sql.PgAlias.getViewDef";

drop alias if exists pg_get_triggerdef;
create alias pg_get_triggerdef deterministic for "com.lealone.plugins.postgresql.sql.PgAlias.getTriggerDef";

drop alias if exists pg_get_ruledef;
create alias pg_get_ruledef deterministic for "com.lealone.plugins.postgresql.sql.PgAlias.getRuleDef";

drop alias if exists pg_get_partkeydef;
create alias pg_get_partkeydef deterministic for "com.lealone.plugins.postgresql.sql.PgAlias.getPartKeyDef";

drop alias if exists pg_get_serial_sequence;
create alias pg_get_serial_sequence deterministic for "com.lealone.plugins.postgresql.sql.PgAlias.getSerialSequence";

drop alias if exists current_database;
create alias current_database deterministic for "com.lealone.plugins.postgresql.sql.PgAlias.getCurrentDatabase";

drop alias if exists current_setting;
create alias current_setting deterministic for "com.lealone.plugins.postgresql.sql.PgAlias.getCurrentSetting";

drop alias if exists pg_backend_pid;
create alias pg_backend_pid deterministic for "com.lealone.plugins.postgresql.sql.PgAlias.getBackendPid";

drop alias if exists pg_size_pretty;
create alias pg_size_pretty deterministic for "com.lealone.plugins.postgresql.sql.PgAlias.getSizePretty";

drop alias if exists obj_description;
create alias obj_description deterministic for "com.lealone.plugins.postgresql.sql.PgAlias.objDescription";

drop alias if exists col_description;
create alias col_description deterministic for "com.lealone.plugins.postgresql.sql.PgAlias.colDescription";

drop alias if exists shobj_description;
create alias shobj_description deterministic for "com.lealone.plugins.postgresql.sql.PgAlias.shobjDescription";

create table pg_catalog.pg_constraint(
    oid int,
    conname varchar_ignorecase,
    connamespace int,
    contype varchar,
    condeferrable boolean,
    condeferred boolean,
    convalidated boolean,
    conrelid int,
    contypid int,
    conindid int,
    confrelid int,
    confupdtype varchar,
    confdeltype varchar,
    confmatchtype varchar,
    conislocal boolean,
    coninhcount int,
    connoinherit boolean,
    conkey array,
    confkey array,
    conpfeqop array,
    conppeqop array,
    conffeqop array,
    conexclop array,
    conbin varchar_ignorecase
);
grant select on pg_catalog.pg_constraint to public;

create table pg_catalog.pg_inherits(
    inhrelid int,
    inhparent int,
    inhseqno int
);
grant select on pg_catalog.pg_inherits to public;

create table pg_catalog.pg_rewrite(
    oid int,
    rulename varchar_ignorecase,
    ev_class int,
    ev_type varchar,
    is_instead boolean,
    ev_enabled varchar
);
grant select on pg_catalog.pg_rewrite to public;

create table pg_catalog.pg_policy(
    oid int,
    polname varchar_ignorecase,
    polrelid int,
    polcmd varchar,
    polpermissive boolean
);
grant select on pg_catalog.pg_policy to public;

create table pg_catalog.pg_foreign_table(
    ftrelid int,
    ftserver int
);
grant select on pg_catalog.pg_foreign_table to public;

create table pg_catalog.pg_publication(
    oid int,
    pubname varchar_ignorecase,
    pubowner int,
    puballtables boolean,
    pubinsert boolean,
    pubupdate boolean,
    pubdelete boolean
);
grant select on pg_catalog.pg_publication to public;

create table pg_catalog.pg_collation(
    oid int,
    collname varchar_ignorecase,
    collnamespace int,
    collowner int,
    collprovider varchar,
    collcollate varchar_ignorecase,
    collctype varchar_ignorecase
);
grant select on pg_catalog.pg_collation to public;

create table pg_catalog.pg_statistic_ext(
    oid int,
    stxname varchar_ignorecase,
    stxnamespace int,
    stxowner int,
    stxrelid int
);
grant select on pg_catalog.pg_statistic_ext to public;

insert into pg_catalog.pg_collation values(100, 'default', 10, 0, 'd', 'en_US.utf8', 'en_US.utf8');
insert into pg_catalog.pg_collation values(101, 'C', 10, 0, 'c', 'C', 'C');
insert into pg_catalog.pg_collation values(102, 'POSIX', 10, 0, 'c', 'POSIX', 'POSIX');
